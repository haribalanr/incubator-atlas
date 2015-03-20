/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metadata.web.resources;

import com.google.common.base.Preconditions;
import org.apache.hadoop.metadata.MetadataServiceClient;
import org.apache.hadoop.metadata.discovery.DiscoveryException;
import org.apache.hadoop.metadata.discovery.DiscoveryService;
import org.apache.hadoop.metadata.web.util.Servlets;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jersey Resource for metadata operations.
 *
 * The entry point for all operations against various aspects of the entities graph.
 *
 * For instance,
 * 	lineage: given an entity, X, get me the lineage - all entities X is derived from (recursively)
 * 	'search': find entities generated by Hive processes or that were generated by Sqoop, etc.
 */
@Path("discovery")
@Singleton
public class MetadataDiscoveryResource {

    private static final Logger LOG = LoggerFactory.getLogger(EntityResource.class);

    private final DiscoveryService discoveryService;

    /**
     * Created by the Guice ServletModule and injected with the
     * configured DiscoveryService.
     *
     * @param discoveryService metadata service handle
     */
    @Inject
    public MetadataDiscoveryResource(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Search using a given query.
     *
     * @param query search query in raw gremlin or DSL format falling back to full text.
     * @return JSON representing the type and results.
     */
    @GET
    @Path("search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response search(@QueryParam("query") String query) {
        Preconditions.checkNotNull(query, "query cannot be null");

        if (query.startsWith("g.")) { // raw gremlin query
            return searchUsingGremlinQuery(query);
        }

        try {
            JSONObject response = new JSONObject();
            response.put(MetadataServiceClient.REQUEST_ID, Servlets.getRequestId());
            response.put("query", query);

            try {   // fall back to dsl
                final String jsonResult = discoveryService.searchByDSL(query);
                response.put("queryType", "dsl");
                response.put(MetadataServiceClient.RESULTS, new JSONObject(jsonResult));

            } catch (Throwable throwable) {
                LOG.error("Unable to get entity list for query {} using dsl", query, throwable);

                // todo: fall back to full text search
                response.put("queryType", "full-text");
                response.put(MetadataServiceClient.RESULTS, new JSONObject());
            }

            return Response.ok(response).build();
        } catch (JSONException e) {
            LOG.error("Unable to get entity list for query {}", query, e);
            throw new WebApplicationException(
                    Servlets.getErrorResponse(e, Response.Status.INTERNAL_SERVER_ERROR));
        }
    }

    /**
     * Search using query DSL format.
     *
     * @param dslQuery search query in DSL format.
     * @return JSON representing the type and results.
     */
    @GET
    @Path("search/dsl")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchUsingQueryDSL(@QueryParam("query") String dslQuery) {
        Preconditions.checkNotNull(dslQuery, "dslQuery cannot be null");

        try {
            final String jsonResult = discoveryService.searchByDSL(dslQuery);

            JSONObject response = new JSONObject();
            response.put(MetadataServiceClient.REQUEST_ID, Servlets.getRequestId());
            response.put("query", dslQuery);
            response.put("queryType", "dsl");
            response.put(MetadataServiceClient.RESULTS, new JSONObject(jsonResult));

            return Response.ok(response).build();
        } catch (DiscoveryException e) {
            LOG.error("Unable to get entity list for dslQuery {}", dslQuery, e);
            throw new WebApplicationException(
                    Servlets.getErrorResponse(e, Response.Status.BAD_REQUEST));
        } catch (JSONException e) {
            LOG.error("Unable to get entity list for dslQuery {}", dslQuery, e);
            throw new WebApplicationException(
                    Servlets.getErrorResponse(e, Response.Status.INTERNAL_SERVER_ERROR));
        }
    }

    /**
     * Search using raw gremlin query format.
     *
     * @param gremlinQuery search query in raw gremlin format.
     * @return JSON representing the type and results.
     */
    @GET
    @Path("search/gremlin")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchUsingGremlinQuery(@QueryParam("query") String gremlinQuery) {
        Preconditions.checkNotNull(gremlinQuery, "gremlinQuery cannot be null");

        try {
            final List<Map<String, String>> results = discoveryService
                    .searchByGremlin(gremlinQuery);

            JSONObject response = new JSONObject();
            response.put(MetadataServiceClient.REQUEST_ID, Servlets.getRequestId());
            response.put("query", gremlinQuery);
            response.put("queryType", "gremlin");

            JSONArray list = new JSONArray();
            for (Map<String, String> result : results) {
                list.put(new JSONObject(result));
            }
            response.put(MetadataServiceClient.RESULTS, list);
            response.put(MetadataServiceClient.TOTAL_SIZE, list.length());

            return Response.ok(response).build();
        } catch (DiscoveryException e) {
            LOG.error("Unable to get entity list for gremlinQuery {}", gremlinQuery, e);
            throw new WebApplicationException(
                    Servlets.getErrorResponse(e, Response.Status.BAD_REQUEST));
        } catch (JSONException e) {
            LOG.error("Unable to get entity list for gremlinQuery {}", gremlinQuery, e);
            throw new WebApplicationException(
                    Servlets.getErrorResponse(e, Response.Status.INTERNAL_SERVER_ERROR));
        }
    }

    /**
     * Return a list of Vertices and Edges that emanate from the provided GUID to the depth
     * specified.
     *
     * GET http://host/api/metadata/discovery/search/relationships/{guid}
     *
     * edgesToFollow = comma-separated list of Labels to follow.  Sample query:
     * http://host/api/metadata/discovery/search/relationships/1?depth=3&edgesToFollow=Likes,Has
     */
    @GET
    @Path("/search/relationships/{guid}")
    @Produces({MediaType.APPLICATION_JSON})
    public Response getLineageResults(@PathParam("guid") final String guid,
                                      @DefaultValue("1") @QueryParam("depth") final int depth,
                                      @QueryParam("edgesToFollow") final String edgesToFollow) {

        LOG.info("Performing GUID lineage search for guid= {}", guid);
        Preconditions.checkNotNull(guid, "Invalid argument: \"guid\" cannot be null.");
        Preconditions
                .checkNotNull(edgesToFollow, "Invalid argument: \"edgesToFollow\" cannot be null.");

        // Parent JSON Object
        JSONObject response = new JSONObject();
        Map<String, HashMap<String, JSONObject>> resultMap = discoveryService
                .relationshipWalk(guid, depth, edgesToFollow);

        try {
            response.put(MetadataServiceClient.REQUEST_ID, Servlets.getRequestId());
            if (resultMap.containsKey("vertices")) {
                response.put("vertices", new JSONObject(resultMap.get("vertices")));
            }
            if (resultMap.containsKey("edges")) {
                response.put("edges", new JSONObject(resultMap.get("edges")));
            }
        } catch (JSONException e) {
            throw new WebApplicationException(
                    Servlets.getErrorResponse("Search: Error building JSON result set.",
                            Response.Status.INTERNAL_SERVER_ERROR));
        }
        LOG.debug("JSON result:" + response.toString());
        return Response.ok(response).build();
    }

    /**
     * Return a list of Vertices and Edges that match the given query.
     *
     * GET http://host/api/metadata/discovery/search/fulltext
     *
     * Sample query:
     * http://host/api/metadata/discovery/search/fulltext?depth=1&property=Name&text=Zack
     *
     * Comma separated list of types as qeury.
     */
    @GET
    @Path("/search/fulltext")
    @Produces({MediaType.APPLICATION_JSON})
    public Response getFullTextResults(@QueryParam("text") final String searchText,
                                       @DefaultValue("1") @QueryParam("depth") final int depth,
                                       @DefaultValue("guid") @QueryParam("property") final String
                                               prop) {

        LOG.info("Performing full text search for vertices with property {} matching= {}", prop,
                searchText);
        Preconditions.checkNotNull(searchText, "Invalid argument: \"text\" cannot be null.");
        Preconditions.checkNotNull(prop, "Invalid argument: \"prop\" cannot be null.");

        // Parent JSON Object
        JSONObject response = new JSONObject();

        Map<String, HashMap<String, JSONObject>> resultMap = discoveryService.textSearch(
                searchText, depth, prop);

        try {
            response.put(MetadataServiceClient.REQUEST_ID, Servlets.getRequestId());
            if (resultMap.containsKey("vertices")) {
                response.put("vertices", resultMap.get("vertices"));
            }
            if (resultMap.containsKey("edges")) {
                response.put("edges", resultMap.get("edges"));
            }
        } catch (JSONException e) {
            throw new WebApplicationException(
                    Servlets.getErrorResponse("Search: Error building JSON result set.",
                            Response.Status.INTERNAL_SERVER_ERROR));
        }

        LOG.debug("JSON result:" + response.toString());
        return Response.ok(response).build();

    }

    /**
     * Return a list Vertices and Edges in the index.
     *
     * GET http://host/api/metadata/discovery/getIndexedFields
     *
     * No parameters taken.
     */
    @GET
    @Path("/getIndexedFields")
    @Produces({MediaType.APPLICATION_JSON})
    public Response getLineageResults() {
        JSONObject response = new JSONObject();

        try {
            response.put("indexed_fields:", discoveryService.getGraphIndexedFields());
        } catch (JSONException e) {
            throw new WebApplicationException(
                    Servlets.getErrorResponse("Search: Error building JSON result set.",
                            Response.Status.INTERNAL_SERVER_ERROR));
        }

        LOG.debug("JSON result:" + response.toString());
        return Response.ok(response).build();
    }
}
