/*
 * Copyright (C) 2016 Orange
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.orange.ngsi2.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.orange.ngsi2.model.Attribute;
import com.orange.ngsi2.model.Entity;
import com.orange.ngsi2.model.Paginated;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureAdapter;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * NGSIv2 API Client
 */
public class Ngsi2Client {

    private static final String basePath = "v2";
    private static final String entitiesPath = basePath + "/entities";
    private static final String entityPath = entitiesPath + "/{entity}";
    private static final String typesPath = basePath + "/types";
    private static final String registrationsPath = basePath + "/subscriptions";
    private static final String baseSubscriptions = basePath + "/subscriptions";
    private static final String attributePath = entityPath + "/attrs/{attributeName}";
    private static final String valuePath = "/value";
    private static final String pathSep = "/";

    private final static Map<String, ?> noParams = Collections.emptyMap();

    private AsyncRestTemplate asyncRestTemplate;

    private HttpHeaders httpHeaders;

    private String baseURL;

    private Ngsi2Client() {
        // set default headers for Content-Type and Accept to application/JSON
        httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
    }

    /**
     * Default constructor
     * @param asyncRestTemplate AsyncRestTemplate to handle requests
     * @param baseURL base URL for the NGSIv2 service
     */
    public Ngsi2Client(AsyncRestTemplate asyncRestTemplate, String baseURL) {
        this();
        this.asyncRestTemplate = asyncRestTemplate;
        this.baseURL = baseURL;

        // Inject NGSI2 error handler and Java 8 support
        injectNgsi2ErrorHandler();
        injectJava8ObjectMapper();
    }

    /**
     * @return the list of supported operations under /v2
     */
    public ListenableFuture<Map<String, String>> getV2() {
        ListenableFuture<ResponseEntity<JsonNode>> responseFuture = request(HttpMethod.GET, baseURL + basePath, null, JsonNode.class);
        return new ListenableFutureAdapter<Map<String, String>, ResponseEntity<JsonNode>>(responseFuture) {
            @Override
            protected Map<String, String> adapt(ResponseEntity<JsonNode> result) throws ExecutionException {
                Map<String, String> services = new HashMap<>();
                result.getBody().fields().forEachRemaining(entry -> services.put(entry.getKey(), entry.getValue().textValue()));
                return services;
            }
        };
    }

    /*
     * Entities requests
     */

    /**
     * Retrieve a list of Entities (simplified)
     * @param ids an optional list of entity IDs (cannot be used with idPatterns)
     * @param idPattern an optional pattern of entity IDs (cannot be used with ids)
     * @param types an optional list of types of entity
     * @param attrs an optional list of attributes to return for all entities
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a pagined list of Entities
     */
    public ListenableFuture<Paginated<Entity>> getEntities(Collection<String> ids, String idPattern,
            Collection<String> types, Collection<String> attrs,
            int offset, int limit, boolean count) {

        return getEntities(ids, idPattern, types, attrs, null, null, null, null, null, offset, limit, count);
    }

    /**
     * Retrieve a list of Entities
     * @param ids an optional list of entity IDs (cannot be used with idPatterns)
     * @param idPattern an optional pattern of entity IDs (cannot be used with ids)
     * @param types an optional list of types of entity
     * @param attrs an optional list of attributes to return for all entities
     * @param query an optional Simple Query Language query
     * @param georel an optional Geo query
     * @param geometry an optional geometry
     * @param coords an optional coordinate
     * @param orderBy an option list of attributes to difine the order of entities
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a pagined list of Entities
     */
    public ListenableFuture<Paginated<Entity>> getEntities(Collection<String> ids, String idPattern,
            Collection<String> types, Collection<String> attrs,
            String query, String georel, String geometry, String coords,
            Collection<String> orderBy,
            int offset, int limit, boolean count) {

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(entitiesPath);
        addParam(builder, "id", ids);
        addParam(builder, "idPattern", idPattern);
        addParam(builder, "type", types);
        addParam(builder, "attrs", attrs);
        addParam(builder, "query", query);
        addParam(builder, "georel", georel);
        addParam(builder, "geometry", geometry);
        addParam(builder, "coords", coords);
        addParam(builder, "orderBy", orderBy);
        addPaginationParams(builder, offset, limit);
        if (count) {
            addParam(builder, "options", "count");
        }

        ListenableFuture<ResponseEntity<Entity[]>> e = request(HttpMethod.GET, builder.toUriString(), null, Entity[].class);
        return new ListenableFutureAdapter<Paginated<Entity>, ResponseEntity<Entity[]>>(e) {
            @Override
            protected Paginated<Entity> adapt(ResponseEntity<Entity[]> result) throws ExecutionException {
                return new Paginated<>(Arrays.asList(result.getBody()), offset, limit, extractTotalCount(result));
            }
        };
    }

    /**
     * Create a new entity
     * @param entity the Entity to add
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> addEntity(Entity entity) {
        return adapt(request(HttpMethod.POST, UriComponentsBuilder.fromHttpUrl(baseURL).path(entitiesPath).toUriString(), entity, Void.class));
    }

    /**
     * Get an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attrs the list of attributes to retreive for this entity, null or empty means all attributes
     * @return the entity
     */
    public ListenableFuture<Entity> getEntity(String entityId, String type, Collection<String> attrs) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(entityPath);
        addParam(builder, "type", type);
        addParam(builder, "attrs", attrs);
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityId).toUriString(), null, Entity.class));
    }

    /**
     * Update existing or append some attributes to an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributes the attributes to update or to append
     * @param append if true, will only allow to append new attributes
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> updateEntity(String entityId, String type, Map<String, Attribute> attributes, boolean append) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(entityPath);
        addParam(builder, "type", type);
        if (append) {
            addParam(builder, "options", "append");
        }
        return adapt(request(HttpMethod.POST, builder.buildAndExpand(entityId).toUriString(), attributes, Void.class));
    }

    /**
     * Replace all the existing attributes of an entity with a new set of attributes
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributes the new set of attributes
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> replaceEntity(String entityId, String type, Map<String, Attribute> attributes) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(entityPath);
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.PUT, builder.buildAndExpand(entityId).toUriString(), attributes, Void.class));
    }

    /**
     * Delete an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> deleteEntity(String entityId, String type) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(entityPath);
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.DELETE, builder.buildAndExpand(entityId).toUriString(), null, Void.class));
    }

    /*
     * Attributes requests
     */

    /**
     * Retrieve the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Attribute> getAttribute(String entityId, String type, String attributeName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(attributePath);
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityId, attributeName).toUriString(), null, Attribute.class));
    }

    /**
     * Update the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Void> updateAttribute(String entityId, String type, String attributeName, Attribute attribute) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(attributePath);
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.PUT, builder.buildAndExpand(entityId, attributeName).toUriString(), attribute, Void.class));
    }

    /**
     * Delete the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Attribute> deleteAttribute(String entityId, String type, String attributeName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path(attributePath);
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.DELETE, builder.buildAndExpand(entityId, attributeName).toUriString(), null, Attribute.class));
    }

    /**
     * Default headers
     * @return the default headers
     */
    public HttpHeaders getHttpHeaders() {
        return httpHeaders;
    }

    /**
     * Make an HTTP request
     */
    protected <T,U> ListenableFuture<ResponseEntity<T>> request(HttpMethod method, String uri, U body, Class<T> responseType) {
        HttpEntity<U> requestEntity = new HttpEntity<>(body, getHttpHeaders());
        return asyncRestTemplate.exchange(uri, method, requestEntity, responseType);
    }

    private <T> ListenableFuture<T> adapt(ListenableFuture<ResponseEntity<T>> responseEntityListenableFuture) {
        return new ListenableFutureAdapter<T, ResponseEntity<T>>(responseEntityListenableFuture) {
            @Override
            protected T adapt(ResponseEntity<T> result) throws ExecutionException {
                return result.getBody();
            }
        };
    }

    private void addPaginationParams(UriComponentsBuilder builder, int offset, int limit) {
        if (offset > 0) {
            builder.queryParam("offset", offset);
        }
        if (limit > 0) {
            builder.queryParam("limit", limit);
        }
    }

    private void addParam(UriComponentsBuilder builder, String key, String value) {
        if (!nullOrEmpty(value)) {
            builder.queryParam(key, value);
        }
    }

    private void addParam(UriComponentsBuilder builder, String key, Collection<? extends CharSequence> value) {
        if (!nullOrEmpty(value)) {
            builder.queryParam(key, String.join(",", value));
        }
    }

    private boolean nullOrEmpty(Collection i) {
        return i == null || i.isEmpty();
    }

    private boolean nullOrEmpty(String i) {
        return i == null || i.isEmpty();
    }

    private int extractTotalCount(ResponseEntity responseEntity) {
        String total = responseEntity.getHeaders().getFirst("X-Total-Count");
        try {
            return Integer.parseInt(total);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Inject the Ngsi2ResponseErrorHandler
     */
    protected void injectNgsi2ErrorHandler() {
        MappingJackson2HttpMessageConverter converter = getMappingJackson2HttpMessageConverter();
        if (converter != null) {
            this.asyncRestTemplate.setErrorHandler(new Ngsi2ResponseErrorHandler(converter.getObjectMapper()));
        }
    }

    /**
     * Inject an ObjectMapper supporting Java8 by default
     */
    protected void injectJava8ObjectMapper() {
        MappingJackson2HttpMessageConverter converter = getMappingJackson2HttpMessageConverter();
        if (converter != null) {
            converter.getObjectMapper().registerModule(new Jdk8Module());
        }
    }

    private MappingJackson2HttpMessageConverter getMappingJackson2HttpMessageConverter() {
        for(HttpMessageConverter httpMessageConverter : asyncRestTemplate.getMessageConverters()) {
            if (httpMessageConverter instanceof MappingJackson2HttpMessageConverter) {
                return (MappingJackson2HttpMessageConverter)httpMessageConverter;
            }
        }
        return null;
    }
}
