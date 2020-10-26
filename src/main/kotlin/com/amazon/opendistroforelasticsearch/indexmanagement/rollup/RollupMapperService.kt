/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.indexmanagement.rollup

import com.amazon.opendistroforelasticsearch.indexmanagement.IndexManagementIndices
import com.amazon.opendistroforelasticsearch.indexmanagement.elasticapi.suspendUntil
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.action.mapping.UpdateRollupMappingAction
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.action.mapping.UpdateRollupMappingRequest
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.Rollup
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.settings.RollupSettings
import com.amazon.opendistroforelasticsearch.indexmanagement.util.IndexUtils.Companion._META
import com.amazon.opendistroforelasticsearch.indexmanagement.util._DOC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.elasticsearch.ResourceAlreadyExistsException
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse
import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.Client
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.metadata.MappingMetadata
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.transport.RemoteTransportException

// TODO: Handle existing rollup indices, validation of fields across source and target indices
//  overwriting existing rollup data, using mappings from source index
class RollupMapperService(
    val client: Client,
    val clusterService: ClusterService,
    private val indexNameExpressionResolver: IndexNameExpressionResolver
) {

    private val logger = LogManager.getLogger(javaClass)

    suspend fun init(rollup: Rollup): Boolean {
        if (indexExists(rollup.targetIndex)) {
            return initExistingRollupIndex(rollup)
        } else {
            createRollupTargetIndex(rollup)
        }
        return true
    }

    // If the index already exists we need to verify it's a rollup index,
    // confirm it does not conflict with existing jobs and is a valid job
    @Suppress("ReturnCount")
    private suspend fun initExistingRollupIndex(rollup: Rollup): Boolean {
        if (isRollupIndex(rollup.targetIndex)) {
            if (jobExistsInRollupIndex(rollup)) {
                return true
            } else {
                return updateRollupIndexMappings(rollup)
            }
        } else {
            // TODO: If we reach here this can never be resolved by the plugin -- the user
            //  has to fix the job or the index themselves so we need to permanently fail the job
            return false
        }
    }

    // This creates the target index if it doesn't already exist
    // Should reject if the target index exists and is not a rolled up index
    // TODO: error handling
    @Suppress("ReturnCount")
    suspend fun createRollupTargetIndex(job: Rollup): Boolean {
        if (indexExists(job.targetIndex)) return isRollupIndex(job.targetIndex)
        try {
            val request = CreateIndexRequest(job.targetIndex)
                .settings(Settings.builder().put(RollupSettings.ROLLUP_INDEX.key, true).build())
                .mapping(_DOC, IndexManagementIndices.rollupTargetMappings, XContentType.JSON)
                // TODO: Perhaps we can do better than this for mappings... as it'll be dynamic for rest
                //  Can we read in the actual mappings from the source index and use that?
                //  Can it have issues with metrics? i.e. an int mapping with 3, 5, 6 added up and divided by 3 for avg is 14/3 = 4.6666
                //  What happens if the first indexing is an integer, i.e. 3 + 3 + 3 = 9/3 = 3 and it saves it as int
                //  and then the next is float and it fails or rounds it up? Does elasticsearch dynamically resolve to int?
            val response: CreateIndexResponse = client.admin().indices().suspendUntil { create(request, it) }
            // Test should not be able to put rollup metadata in non rollup index

            if (response.isAcknowledged) {
                return updateRollupIndexMappings(job)
            }
        } catch (e: RemoteTransportException) {
            logger.info("RemoteTransportException") // TODO: handle resource already exists too
        } catch (e: ResourceAlreadyExistsException) {
            logger.warn("Failed to create ${job.targetIndex} as it already exists - checking if we can add ${job.id}")
            // TODO: Check if targetIndex is a rollup index and update mappings
        } catch (e: Exception) {
            logger.error("Failed to create ${job.targetIndex}", e) // TODO
        }
        return false
    }

    // Source index can be a pattern so will need to resolve the index to concrete indices and check:
    // 1. If there are any indices resolving to the given source index
    // 2. That each concrete index is valid (in terms of mappings, etc.)
    fun isSourceIndexValid(index: String): Boolean {
        // Allow no indices, open and closed
        // Rolling up on closed indices will not be caught here
        val concreteIndices = indexNameExpressionResolver.concreteIndexNames(clusterService.state(), IndicesOptions.lenientExpand(), index)
        // TODO: Validate mappings of concrete indices
        // TODO: Add some entry in metadata that will store index -> indexUUID for validated indices
        //  That way, we only need to validate indices that aren't in the metadata (covers cases where new index with same name was made)
        return concreteIndices.isNotEmpty()
    }

    // TODO: error handling
    // TODO: nulls, ie index response is null
    suspend fun jobExistsInRollupIndex(rollup: Rollup): Boolean {
        val req = GetMappingsRequest().indices(rollup.targetIndex)
        val res: GetMappingsResponse = client.admin().indices().suspendUntil { getMappings(req, it) }

        val indexMapping: MappingMetadata = res.mappings[rollup.targetIndex][_DOC]

        return ((indexMapping.sourceAsMap?.get(_META) as Map<*, *>?)?.get(ROLLUPS) as Map<*, *>?)?.containsKey(rollup.id) == true
    }

    fun isRollupIndex(index: String): Boolean = RollupSettings.ROLLUP_INDEX.get(clusterService.state().metadata.index(index).settings)

    fun indexExists(index: String): Boolean = clusterService.state().routingTable.hasIndex(index)

    // TODO: error handling
    // TODO: The use of the master transport action UpdateRollupMappingAction will prevent
    //   overwriting an existing rollup job _meta by checking for the job id
    //   but there is still a race condition if two jobs are added at the same time for the
    //   same target index. There is a small time window after get mapping and put mappings
    //   where they can both get the same mapping state and only add their own job, meaning one
    //   of the jobs won't be added to the target index _meta
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun updateRollupIndexMappings(rollup: Rollup): Boolean {
        return withContext(Dispatchers.IO) {
            val resp: AcknowledgedResponse = client.suspendUntil {
                execute(UpdateRollupMappingAction.INSTANCE, UpdateRollupMappingRequest(rollup), it)
            }
            resp.isAcknowledged
        }
    }

    companion object {
        const val ROLLUPS = "rollups"
    }
}