/*
 * Copyright (C) 2013 Glencoe Software, Inc. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package omero.cmd.graphs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.builder.HashCodeBuilder;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import ome.api.IQuery;
import ome.parameters.Parameters;
import omero.cmd.Chgrp;
import omero.cmd.Delete;
import omero.cmd.DoAll;
import omero.cmd.GraphModify;
import omero.cmd.Helper;
import omero.cmd.Request;

/**
 * Preprocessors have a chance to modify the list of
 * {@link Request} instances which are passed to a
 * {@link DoAll}. If this strategy is continued, this
 * should be refactored behind a discoverable interface.
 *
 * @author Josh Moore, josh at glencoesoftware.com
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.0
 */
public class Preprocessor {
    /**
     * The types of target for which we care about adjusting graph operation requests.
     * @author m.t.b.carroll@dundee.ac.uk
     * @since 5.0
     */
    protected static enum TargetType {
        IMAGE, FILESET, DATASET, PROJECT, WELL, PLATE, SCREEN;

        /** lookup from initial-capital name to type, e.g. "/Image" to {@link TargetType#IMAGE} */
        public static final ImmutableMap<String, TargetType> byName;
        /** lookup from type to initial-capital name, e.g. {@link TargetType#IMAGE} to "/Image" */
        public static final ImmutableMap<TargetType, String> byType;

        static {
            final Builder<String, TargetType> byNameBuilder = ImmutableMap.builder();
            final Builder<TargetType, String> byTypeBuilder = ImmutableMap.builder();
            for (final TargetType value : TargetType.values()) {
                final String name = '/' + value.name().substring(0, 1) + value.name().substring(1).toLowerCase();
                byNameBuilder.put(name, value);
                byTypeBuilder.put(value, name);
            }
            byName = byNameBuilder.build();
            byType = byTypeBuilder.build();
        }
    }

    /**
     * A tuple of target type and target ID.
     * Instances with the same type and ID are {@link #equals(Object)} and have the same {@link #hashCode()}.
     * @author m.t.b.carroll@dundee.ac.uk
     * @since 5.0
     */
    protected static class GraphModifyTarget {
        public final TargetType targetType;
        public final long targetId;

        /**
         * Construct a target type-ID tuple.
         * @param targetType the target type
         * @param targetId the target ID
         */
        public GraphModifyTarget(TargetType targetType, long targetId) {
            if (targetType == null) {
                throw new IllegalArgumentException("target type must be set");
            }
            this.targetType = targetType;
            this.targetId = targetId;
        }

        /**
         * Return only the targets of the given type.
         * @param targets some targets
         * @param targetType the desired type of target
         * @return the targets of the desired type
         */
        public static List<GraphModifyTarget> filterByType(Collection<GraphModifyTarget> targets, TargetType targetType) {
            final List<GraphModifyTarget> filtered = new ArrayList<GraphModifyTarget>();
            for (final GraphModifyTarget target : targets) {
                if (target.targetType == targetType) {
                    filtered.add(target);
                }
            }
            return filtered;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof GraphModifyTarget) {
                final GraphModifyTarget same = (GraphModifyTarget) o;
                return this.targetType == same.targetType && this.targetId == same.targetId;
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return new HashCodeBuilder().append(this.targetType.ordinal()).append(this.targetId).toHashCode();
        }

        @Override
        public String toString() {
            return this.targetType + ":" + this.targetId;
        }
    }

    private static final ImmutableList<Class<? extends GraphModify>> graphOperations = ImmutableList.of(Chgrp.class, Delete.class);

    protected static final ImmutableList<Entry<TargetType, TargetType>> targetTypeHierarchy;

    static {
        final com.google.common.collect.ImmutableList.Builder<Entry<TargetType, TargetType>> builder = ImmutableList.builder();
        builder.add(Maps.immutableEntry(TargetType.PROJECT, TargetType.DATASET));
        builder.add(Maps.immutableEntry(TargetType.DATASET, TargetType.IMAGE));
        builder.add(Maps.immutableEntry(TargetType.SCREEN,  TargetType.PLATE));
        builder.add(Maps.immutableEntry(TargetType.PLATE,   TargetType.WELL));
        builder.add(Maps.immutableEntry(TargetType.WELL,    TargetType.IMAGE));
        builder.add(Maps.immutableEntry(TargetType.FILESET, TargetType.IMAGE));
        targetTypeHierarchy = builder.build();
    }

    /* TODO: batch querying on multiple IDs at once as in ome.logic.PojosImpl.getImagesBySplitFilesets */

    protected static final ImmutableMap<Entry<TargetType, TargetType>, String> hqlFromTo;

    static {
        final Builder<Entry<TargetType, TargetType>, String> builder = ImmutableMap.builder();
        builder.put(Maps.immutableEntry(TargetType.PROJECT, TargetType.DATASET),
                "select child.id from ProjectDatasetLink where parent.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.DATASET, TargetType.IMAGE),
                "select child.id from DatasetImageLink where parent.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.SCREEN, TargetType.PLATE),
                "select child.id from ScreenPlateLink where parent.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.PLATE, TargetType.WELL),
                "select id from Well where plate.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.WELL, TargetType.IMAGE),
                "select image.id from WellSample where well.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.FILESET, TargetType.IMAGE),
                "select id from Image where fileset.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.IMAGE, TargetType.FILESET),
                "select fileset.id from Image where fileset.id is not null and id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.IMAGE, TargetType.WELL),
                "select well.id from WellSample where image.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.WELL, TargetType.PLATE),
                "select plate.id from Well where id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.PLATE, TargetType.SCREEN),
                "select parent.id from ScreenPlateLink where child.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.IMAGE, TargetType.DATASET),
                "select parent.id from DatasetImageLink where child.id = :" + Parameters.ID);
        builder.put(Maps.immutableEntry(TargetType.DATASET, TargetType.PROJECT),
                "select parent.id from ProjectDatasetLink where child.id = :" + Parameters.ID);
        hqlFromTo = builder.build();
    }

    private final List<Request> requests;

    private final Helper helper;

    /** cache of containers that have been looked up */
    protected final SetMultimap<GraphModifyTarget, GraphModifyTarget> containedByContainer = HashMultimap.create();

    /** cache of contained that have been looked up */
    protected final SetMultimap<GraphModifyTarget, GraphModifyTarget> containerByContained = HashMultimap.create();

    /** note of which contained have been looked up */
    protected final Set<Map.Entry<TargetType, GraphModifyTarget>> lookupContainedDone = Sets.newHashSet();

    /** note of which containers have been looked up */
    protected final Set<Map.Entry<TargetType, GraphModifyTarget>> lookupContainerDone = Sets.newHashSet();

    /**
     * Transform the list of requests.
     * A prohibited prefix must be in the list, and the first must precede any prohibited suffixes.
     * @param isRelevant which type of graph operation's requests to transform
     * @param newRequestTarget the target of the new request to create to replace the removed ones
     * @param prohibitedPrefixes the requests that may not precede the new request
     * @param prohibitedSuffixes the requests that may be omitted after the new request
     */
    protected final void transform(Predicate<Request> isRelevant, GraphModifyTarget newRequestTarget,
            Set<GraphModifyTarget> prohibitedPrefixes, Set<GraphModifyTarget> prohibitedSuffixes) {
        boolean added = false;
        int index = 0;
        while (index < this.requests.size()) {
            if (!isRelevant.apply(requests.get(index))) {
                index++;
                continue;
            }
            final GraphModify request = (GraphModify) this.requests.get(index);
            final TargetType targetType = TargetType.byName.get(request.type);
            if (targetType == null) {
                index++;
                continue;
            }
            /* it is a relevant request with an understood target type */
            final GraphModifyTarget requestTarget = new GraphModifyTarget(targetType, request.id);
            /* must we now prefix it? */
            if (!added && prohibitedPrefixes.contains(requestTarget)) {
                added = true;
                /* FIXME: this does not modify user-set options */
                final GraphModify newRequest = (GraphModify) request.clone();
                newRequest.type = TargetType.byType.get(newRequestTarget.targetType);
                newRequest.id = newRequestTarget.targetId;
                this.requests.add(index++, newRequest);
            }
            /* must we remove it? */
            if (prohibitedSuffixes.contains(requestTarget)) {
                if (!added) {
                    throw new IllegalArgumentException("some prohibited prefix must occur before any prohibited suffixes");
                }
                this.requests.remove(index);
            } else {
                index++;
            }
        }
        if (!added) {
            throw new IllegalArgumentException("no prohibited prefix is among the requests");
        }
    }

    public Preprocessor(List<Request> requests, Helper helper) {
        this.requests = requests;
        this.helper = helper;

        process();
    }

    /**
     * @return the size of the Image ID cache
     */
    public long getImageCount() {
        return containerByContained.asMap().size();
    }

    /**
     * @return the size of the container ID cache
     */
    public long getFilesetCount() {
        return containedByContainer.asMap().size();
    }

    /**
     * Returns a copy of the requests field or an empty list if null.
     */
    public List<Request> getRequests() {
        if (requests == null) {
            return new ArrayList<Request>();
        }
        return new ArrayList<Request>(requests);
    }

    /**
     * Add to the cached lookups the given target's container, if any.
     * @param containerType the container type to add
     * @param contained the target that may be contained
     */
    private void lookupContainer(TargetType containerType, GraphModifyTarget contained) {
        if (!lookupContainerDone.add(Maps.immutableEntry(containerType, contained))) {
            return;
        }
        final String queryString = Preprocessor.hqlFromTo.get(Maps.immutableEntry(contained.targetType, containerType));
        if (queryString == null) {
            throw new IllegalArgumentException("not implemented for " + contained.targetType + " to " + containerType);
        }
        final IQuery queryService = helper.getServiceFactory().getQueryService();
        final List<Object[]> containerIds = queryService.projection(queryString, new Parameters().addId(contained.targetId));
        for (final Object[] containerId : containerIds) {
            final GraphModifyTarget container = new GraphModifyTarget(containerType, (Long) containerId[0]);
            lookupContained(contained.targetType, container);
        }
    }

    /**
     * Add to the cached lookups the given container.
     * @param containedType the contained type to add
     * @param container the container
     */
    private void lookupContained(TargetType containedType, GraphModifyTarget container) {
        if (!lookupContainedDone.add(Maps.immutableEntry(containedType, container))) {
            return;
        }
        final String queryString = Preprocessor.hqlFromTo.get(Maps.immutableEntry(container.targetType, containedType));
        if (queryString == null) {
            throw new IllegalArgumentException("not implemented for " + containedType + " from " + container.targetType);
        }
        final IQuery queryService = helper.getServiceFactory().getQueryService();
        final List<Object[]> containedIds = queryService.projection(queryString, new Parameters().addId(container.targetId));
         for (final Object[] containedId : containedIds) {
            final GraphModifyTarget contained = new GraphModifyTarget(containedType, (Long) containedId[0]);
            containedByContainer.put(container, contained);
            containerByContained.put(contained, container);
        }
    }

    /**
     * Assuming that the relevant lookups are cached, recursively find all the direct and indirect containers of the given target.
     * @param contained a target
     * @return all the target's containers
     */
    private Set<GraphModifyTarget> getAllContainers(GraphModifyTarget contained) {
        final Set<GraphModifyTarget> allContainers    = new HashSet<GraphModifyTarget>();
        final Set<GraphModifyTarget> pendingContained = new HashSet<GraphModifyTarget>();
        pendingContained.add(contained);
        while (!pendingContained.isEmpty()) {
            final Iterator<GraphModifyTarget> pendingContainedsIterator = pendingContained.iterator();
            final GraphModifyTarget nextContained = pendingContainedsIterator.next();
            pendingContainedsIterator.remove();
            final Set<GraphModifyTarget> nextContainers = this.containerByContained.get(nextContained);
            allContainers.addAll(nextContainers);
            pendingContained.addAll(nextContainers);
        }
        return allContainers;
    }

    protected void process() {
        for (final Class<? extends GraphModify> op : graphOperations) {

            /* note which targets are somehow referenced by the current operation's requests,
             * also make sure that relevant container relationships are cached */

            final Predicate<Request> isRelevant = new Predicate<Request>() {
                public boolean apply(Request request) {
                    return op.isAssignableFrom(request.getClass()) &&
                            TargetType.byName.get(((GraphModify) request).type) != null;
                }
            };

            final SetMultimap<TargetType, GraphModifyTarget> targets = HashMultimap.create();

            /* direct references */

            for (final Request relevantRequest : Collections2.filter(this.requests, isRelevant)) {
                final GraphModify gm = (GraphModify) relevantRequest;
                final TargetType targetType = TargetType.byName.get(gm.type);
                targets.put(targetType, new GraphModifyTarget(targetType, gm.id));
            }

            /* indirect references */

            for (final Entry<TargetType, TargetType> relationship : Preprocessor.targetTypeHierarchy) {
                final TargetType containerType = relationship.getKey();
                final TargetType containedType = relationship.getValue();
                for (final GraphModifyTarget container : targets.get(containerType)) {
                    lookupContained(containedType, container);
                    targets.putAll(containedType, this.containedByContainer.get(container));
                }
            }

            /* look up the filesets that contain referenced images */

            for (final GraphModifyTarget image : targets.get(TargetType.IMAGE)) {
                lookupContainer(TargetType.FILESET, image);
            }

            /* review the referenced FS images */

            while (!targets.get(TargetType.IMAGE).isEmpty()) {
                final GraphModifyTarget image = targets.get(TargetType.IMAGE).iterator().next();
                /* find the image's fileset */
                final Set<GraphModifyTarget> containers = this.containerByContained.get(image);
                final Iterator<GraphModifyTarget> filesetIterator = 
                        GraphModifyTarget.filterByType(containers, TargetType.FILESET).iterator();
                final GraphModifyTarget fileset;
                if (!filesetIterator.hasNext()) {
                    /* pre-FS image */
                    targets.get(TargetType.IMAGE).remove(image);
                    continue;
                }
                fileset = filesetIterator.next();
                if (filesetIterator.hasNext()) {
                    throw new IllegalStateException("image is contained in multiple filesets");
                }
                /* check that all the fileset's images are referenced */
                final Set<GraphModifyTarget> filesetImages = this.containedByContainer.get(fileset);
                for (final GraphModifyTarget filesetImage : filesetImages) {
                    if (filesetImage.targetType != TargetType.IMAGE) {
                        throw new IllegalStateException("non-image found in fileset");
                    }
                }
                final boolean completeFileset = targets.get(TargetType.IMAGE).containsAll(filesetImages);
                /* this iteration will handle this fileset sufficiently, so do not revisit it */
                targets.get(TargetType.IMAGE).removeAll(filesetImages);
                /* this preprocessing is only needed for when a fileset has multiple images all of which are referenced */
                if (!(filesetImages.size() > 1 && completeFileset)) {
                    continue;
                }
                /* okay, list the fileset as a target among the requests, before any of its images or their containers */
                final Set<GraphModifyTarget> prohibitedPrefixes = new HashSet<GraphModifyTarget>(filesetImages);
                for (final GraphModifyTarget filesetImage : filesetImages) {
                    prohibitedPrefixes.addAll(getAllContainers(filesetImage));
                }
                /* and, once listed, neither the fileset nor its images need subsequent requests */
                final Set<GraphModifyTarget> prohibitedSuffixes = new HashSet<GraphModifyTarget>(filesetImages);
                prohibitedSuffixes.add(fileset);
                /* adjust the list of requests accordingly */
                transform(isRelevant, fileset, prohibitedPrefixes, prohibitedSuffixes);
            }
        }
    }
}
