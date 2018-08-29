/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ml.MlMetaIndex;
import org.elasticsearch.xpack.core.ml.action.PutFilterAction;
import org.elasticsearch.xpack.core.ml.action.UpdateFilterAction;
import org.elasticsearch.xpack.core.ml.job.config.MlFilter;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.core.ml.utils.ToXContentParams;
import org.elasticsearch.xpack.ml.job.JobManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

public class TransportUpdateFilterAction extends HandledTransportAction<UpdateFilterAction.Request, PutFilterAction.Response> {

    private final Client client;
    private final JobManager jobManager;

    @Inject
    public TransportUpdateFilterAction(Settings settings, TransportService transportService, ActionFilters actionFilters, Client client,
                                       JobManager jobManager) {
        super(settings, UpdateFilterAction.NAME, transportService, actionFilters,
                (Supplier<UpdateFilterAction.Request>) UpdateFilterAction.Request::new);
        this.client = client;
        this.jobManager = jobManager;
    }

    @Override
    protected void doExecute(Task task, UpdateFilterAction.Request request, ActionListener<PutFilterAction.Response> listener) {
        ActionListener<FilterWithVersion> filterListener = ActionListener.wrap(filterWithVersion -> {
            updateFilter(filterWithVersion, request, listener);
        }, listener::onFailure);

        getFilterWithVersion(request.getFilterId(), filterListener);
    }

    private void updateFilter(FilterWithVersion filterWithVersion, UpdateFilterAction.Request request,
                              ActionListener<PutFilterAction.Response> listener) {
        MlFilter filter = filterWithVersion.filter;

        if (request.isNoop()) {
            listener.onResponse(new PutFilterAction.Response(filter));
            return;
        }

        String description = request.getDescription() == null ? filter.getDescription() : request.getDescription();
        SortedSet<String> items = new TreeSet<>(filter.getItems());
        items.addAll(request.getAddItems());

        // Check if removed items are present to avoid typos
        for (String toRemove : request.getRemoveItems()) {
            boolean wasPresent = items.remove(toRemove);
            if (wasPresent == false) {
                listener.onFailure(ExceptionsHelper.badRequestException("Cannot remove item [" + toRemove
                        + "] as it is not present in filter [" + filter.getId() + "]"));
                return;
            }
        }

        MlFilter updatedFilter = MlFilter.builder(filter.getId()).setDescription(description).setItems(items).build();
        indexUpdatedFilter(updatedFilter, filterWithVersion.version, request, listener);
    }

    private void indexUpdatedFilter(MlFilter filter, long version, UpdateFilterAction.Request request,
                                    ActionListener<PutFilterAction.Response> listener) {
        IndexRequest indexRequest = new IndexRequest(MlMetaIndex.INDEX_NAME, MlMetaIndex.TYPE, filter.documentId());
        indexRequest.version(version);
        indexRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            ToXContent.MapParams params = new ToXContent.MapParams(Collections.singletonMap(ToXContentParams.INCLUDE_TYPE, "true"));
            indexRequest.source(filter.toXContent(builder, params));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise filter with id [" + filter.getId() + "]", e);
        }

        executeAsyncWithOrigin(client, ML_ORIGIN, IndexAction.INSTANCE, indexRequest, new ActionListener<IndexResponse>() {
            @Override
            public void onResponse(IndexResponse indexResponse) {
                jobManager.notifyFilterChanged(filter, request.getAddItems(), request.getRemoveItems(), ActionListener.wrap(
                        response -> listener.onResponse(new PutFilterAction.Response(filter)),
                        listener::onFailure
                ));
            }

            @Override
            public void onFailure(Exception e) {
                Exception reportedException;
                if (e instanceof VersionConflictEngineException) {
                    reportedException = ExceptionsHelper.conflictStatusException("Error updating filter with id [" + filter.getId()
                            + "] because it was modified while the update was in progress", e);
                } else {
                    reportedException = ExceptionsHelper.serverError("Error updating filter with id [" + filter.getId() + "]", e);
                }
                listener.onFailure(reportedException);
            }
        });
    }

    private void getFilterWithVersion(String filterId, ActionListener<FilterWithVersion> listener) {
        GetRequest getRequest = new GetRequest(MlMetaIndex.INDEX_NAME, MlMetaIndex.TYPE, MlFilter.documentId(filterId));
        executeAsyncWithOrigin(client, ML_ORIGIN, GetAction.INSTANCE, getRequest, new ActionListener<GetResponse>() {
            @Override
            public void onResponse(GetResponse getDocResponse) {
                try {
                    if (getDocResponse.isExists()) {
                        BytesReference docSource = getDocResponse.getSourceAsBytesRef();
                        try (InputStream stream = docSource.streamInput();
                             XContentParser parser = XContentFactory.xContent(XContentType.JSON)
                                     .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, stream)) {
                            MlFilter filter = MlFilter.LENIENT_PARSER.apply(parser, null).build();
                            listener.onResponse(new FilterWithVersion(filter, getDocResponse.getVersion()));
                        }
                    } else {
                        this.onFailure(new ResourceNotFoundException(Messages.getMessage(Messages.FILTER_NOT_FOUND, filterId)));
                    }
                } catch (Exception e) {
                    this.onFailure(e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private static class FilterWithVersion {

        private final MlFilter filter;
        private final long version;

        private FilterWithVersion(MlFilter filter, long version) {
            this.filter = filter;
            this.version = version;
        }
    }
}
