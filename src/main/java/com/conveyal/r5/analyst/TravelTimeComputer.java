package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathWriter;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.analyst.scenario.PickupWaitTimes;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.FareDominatingList;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.path.Path;
import com.esotericsoftware.minlog.Log;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static com.conveyal.r5.analyst.scenario.PickupWaitTimes.NO_SERVICE_HERE;
import static com.conveyal.r5.analyst.scenario.PickupWaitTimes.NO_WAIT_ALL_STOPS;
import static com.conveyal.r5.profile.PerTargetPropagater.MM_PER_METER;

/**
 * This computes a surface representing travel time from one origin to all destination cells, and writes out a
 * flattened 3D array, with each pixel of a 2D grid containing the different percentiles of travel time requested
 * by the frontend. This is called the "access grid" format and is distinct from the "destination grid" format in
 * that holds multiple values per pixel and has no inter-cell delta coding. It also has JSON concatenated on the
 * end with any scenario application warnings.
 *
 * TODO: we should merge these grid formats and update the spec to allow JSON errors at the end.
 * TODO: try to decouple the internal representation of the results from how they're serialized to the API.
 */
public class TravelTimeComputer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeComputer.class);
    private final AnalysisWorkerTask request;
    private final TransportNetwork network;

    /** Constructor. */
    public TravelTimeComputer (AnalysisWorkerTask request, TransportNetwork network) {
        this.request = request;
        this.network = network;
    }

    /**
     * The TravelTimeComputer can make travel time grids, accessibility indicators, or (eventually) both depending
     * on what's in the task it's given. TODO factor out each major step of this process into private methods.
     */
    public OneOriginResult computeTravelTimes() {

        //access 모드를 바꾸면 computeTravelTimes에 진입하기 전에 새롭게 linkage 캐시를 연결한다.
        LOG.debug("=========Computing travel times for task: {}==========", request.taskId);
        // 0. Preliminary range checking and setup =====================================================================
        if (!request.directModes.equals(request.accessModes)) {
            throw new IllegalArgumentException("Direct mode may not be different than access mode in Analysis.");
        }

        // If this request includes a fare calculator, inject the transport network's transit layer into it.
        // This is threadsafe because deserializing each incoming request creates a new fare calculator instance.
        if (request.inRoutingFareCalculator != null) {
            request.inRoutingFareCalculator.transitLayer = network.transitLayer;
        }

        // Create an object that accumulates travel times at each destination, simplifying them into percentiles.
        // TODO Create and encapsulate this object within the propagator.
        TravelTimeReducer travelTimeReducer = new TravelTimeReducer(request, network);

        // Find the set of destinations for a travel time calculation, not yet linked to the street network, and with
        // no associated opportunities. By finding the extents and destinations up front, we ensure the exact same
        // destination pointset is used for all steps below.
        // This reuses the logic for finding the appropriate grid size and linking, which is now in the NetworkPreloader.
        // We could change the preloader to retain these values in a compound return type, to avoid repetition here.
        PointSet destinations;

        if (request instanceof  RegionalTask
            && !request.makeTauiSite
            && request.destinationPointSets[0] instanceof FreeFormPointSet
        ) {
            // 지역 분석, 즉 여러 destination 분석이면서 grid가 아닌 경우
            // request.destinationPointSets[0]을 넣는다. (거기에 FreeFormPointSet이 들어 있다)
            // Freeform; destination pointset was set by handleOneRequest in the main AnalystWorker
            destinations = request.destinationPointSets[0];
        } else {
            // Gridded (non-freeform) destinations. The extents are found differently in regional and single requests.
            WebMercatorExtents destinationGridExtents = request.getWebMercatorExtents();
            // Make a WebMercatorGridPointSet with the right extents, referring to the network's base grid and linkage.
            // destinations 가 zoom과 가장자리 한계로 만들어진다.
            destinations = AnalysisWorkerTask.gridPointSetCache.get(destinationGridExtents, network.fullExtentGridPointSet);
            travelTimeReducer.checkOpportunityExtents(destinations);
        }

        // I. Access to transit (or direct non-transit travel to destination) ==========================================
        // Use one or more modes to access transit stops, retaining the reached transit stops as well as the travel
        // times to the destination points using those access modes.

        // A map from transit stop vertex indices to the travel time (in seconds) and mode used to reach those
        // vertices.
        // 일단 만들어두고 나중에 결과를 업데이트해서 1위로 결정한다.
        StreetTimesAndModes bestAccessOptions = new StreetTimesAndModes();

        // Travel times in seconds to each destination point (or MAX_INT for unreachable points?)
        // Starts out as null but will be updated when any access leg search succeeds.
        //
        PointSetTimes nonTransitTravelTimesToDestinations = null;

        // We will try to find a starting point in the street network and perform an access search with each street mode.
        // This tracks whether any of those searches (for any mode) were successfully connected to the street network.
        boolean foundAnyOriginPoint = false;

        // Convert from profile routing qualified modes to internal modes. This also ensures we don't route on
        // multiple LegModes that have the same StreetMode (such as BIKE and BIKE_RENT).
        EnumSet<StreetMode> accessModes = LegMode.toStreetModeSet(request.accessModes);

        // Perform a street search for each access mode. For now, direct modes must be the same as access modes.
        // BIKE and BIKE_RENT 요청이 동시에 들어올 경우 두 바퀴를 돌 수 밖에 없다.
        // 나머지는 한 바퀴에 수행하는 것으로 항상 끝난다. ( 바로 위에서 한 가지만 return 받도록 되어 있다)
        for (StreetMode accessMode : accessModes) {
            LOG.info("Performing street search for mode: {}", accessMode);

            // Look up pick-up service for an access leg.
            // 픽업 서비스가 있으면 해당 서비스를 반환한다.
            // 픽업 서비스를 적용해야 하는데 유효하지 않을 경우에는 NO_SERVICE_HERE를 반환한다.
            // 픽업 서비스가 상관없는 대부분의 경우에는 NO_WAIT_ALL_STOPS를 반환한다.
            PickupWaitTimes.AccessService accessService =
                    network.streetLayer.getAccessService(request.fromLat, request.fromLon, accessMode);

            // When an on-demand mobility service is defined, it may not be available at this particular location.
            if (accessService == NO_SERVICE_HERE) {
                LOG.info("On-demand {} service is not available at this location, " +
                        "continuing to next access mode (if any).", accessMode);
                continue;
            }

            // Attempt to set the origin point before progressing any further.
            // This allows us to skip routing calculations if the network is entirely inaccessible. In the CAR_PARK
            // case this StreetRouter will be replaced but this still serves to bypass unnecessary computation.
            // The request must be provided to the StreetRouter before setting the origin point.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.profileRequest = request;
            sr.streetMode = accessMode;
            if ( ! sr.setOrigin(request.fromLat, request.fromLon)) {
                // Short circuit around routing and propagation if the origin point was not attached to the street network.
                LOG.info("Origin point could not be linked to the street network for mode {}.", accessMode);
                continue;
            }
            foundAnyOriginPoint = true;

            // The code blocks below identify transit stations reachable from the origin and produce a grid of
            // non-transit travel times that will later be merged with the transit travel times.
            // Note: this is essentially the same thing that is happening when creating linkage cost tables for the
            // egress end of the trip. We could probably reuse a method for both (getTravelTimesFromPoint).
            // Note: Access searches (which minimize travel time) are asymmetric with the egress cost tables (which
            // often minimize distance to allow reuse at different speeds).
            // Preserve past behavior: only apply bike or walk time limits when those modes are used to access transit.
            if (request.hasTransit()) {
                LOG.debug("Routing to transit stops for mode {}", accessMode);
                sr.timeLimitSeconds = request.getMaxTimeSeconds(accessMode);
            } else {
                LOG.debug("Routing directly to destinations for mode {}", accessMode);
                sr.timeLimitSeconds = request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE;
            }
            // Even if generalized cost tags were present on the input data, we always minimize travel time.
            // The generalized cost calculations currently increment time and weight by the same amount.
            // 시간이냐 거리냐 설정
            sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.route();
            // Change to walking in order to reach transit stops in pedestrian-only areas like train stations.
            // This implies you are dropped off or have a very easy parking spot for your vehicle.
            // This kind of multi-stage search should also be used when building egress distance cost tables.
            // walk 모드가 아니면, 도보로 계속 길찾기 하도록 설정
            // 왜냐하면, 차량이 접근하지 못하는 곳에 정류장이 있을 수 있기 때문
            if (accessMode != StreetMode.WALK) {
                sr.keepRoutingOnFoot();
            }

            // 대중교통수단을 타는 길찾기라면, 위에서 찾았던 반경 내의 모든 정류장을 여기서 가져온다
            // 그리고 아래에서 그 정류장들을 기준으로 길찾기를 다시 해서, 최종 결과들을 만드는 것 같다.
            if (request.hasTransit()) {
                // Find access times to transit stops, keeping the minimum across all access street modes.
                // Note that getReachedStops() returns the routing variable units, not necessarily seconds.
                // TODO add logic here if linkedStops are specified in pickupDelay?
                // 위에서 집어넣었던 stops의 Map을 가져온다.
                TIntIntMap travelTimesToStopsSeconds = sr.getReachedStops();
                if (accessService != NO_WAIT_ALL_STOPS) {
                    LOG.info("Delaying transit access times by {} seconds (to wait for {} pick-up).",
                            accessService.waitTimeSeconds, accessMode);
                    if (accessService.stopsReachable != null) {
                        travelTimesToStopsSeconds.retainEntries((k, v) -> accessService.stopsReachable.contains(k));
                    }
                    travelTimesToStopsSeconds.transformValues(i -> i + accessService.waitTimeSeconds);
                }
               bestAccessOptions.update(travelTimesToStopsSeconds, accessMode);
            }

            // Calculate times to reach destinations directly by this street mode, without using transit.
            //
            // The current implementation iterates over every cell in the destination grid. That usually makes sense
            // for non-transit searches, where we need to evaluate times up to the full travel time limit. But for
            // transit searches, where sr.timeLimitSeconds is typically small, it may not make sense to iterate over
            // every cell in a (possibly huge) destination grid. If this is measured to be inefficient, we could
            // construct a sub-grid that's an envelope around sr.originSplit's lat/lon (sized according to sr
            // .timeLimitSeconds and a mode-specific speed?), then iterate over the points in that sub-grid.
            // 목적지까지의 도보 이동 시간을 계산한다. 비교용으로 사용됨
            {
                LinkedPointSet linkedDestinations = network.linkageCache.getLinkage(
                        destinations,
                        network.streetLayer,
                        accessMode
                );

                int streetSpeedMillimetersPerSecond = (int) (request.getSpeedForMode(accessMode) * 1000);
                if (streetSpeedMillimetersPerSecond <= 0) {
                    throw new IllegalArgumentException("Speed of access mode must be greater than 0.");
                }

                // Convert from floating point meters per second (in request) to integer millimeters per second (internal).
                int walkSpeedMillimetersPerSecond = (int) (request.walkSpeed * MM_PER_METER);

                Split origin = sr.getOriginSplit();

                PointSetTimes pointSetTimes = linkedDestinations.eval(
                        sr::getTravelTimeToVertex,
                        streetSpeedMillimetersPerSecond,
                        walkSpeedMillimetersPerSecond,
                        origin
                );

                if (accessService != NO_WAIT_ALL_STOPS) {
                    LOG.info("Delaying direct travel times by {} seconds (to wait for {} pick-up).",
                            accessService.waitTimeSeconds, accessMode);
                    if (accessService.stopsReachable != null) {
                        // Disallow direct travel to destination if pickupDelay zones are associated with stops.
                        pointSetTimes = PointSetTimes.allUnreached(destinations);
                    } else {
                        // Allow direct travel to destination using services not associated with specific stops.
                        pointSetTimes.incrementAllReachable(accessService.waitTimeSeconds);
                    }
                }
                nonTransitTravelTimesToDestinations = PointSetTimes.minMerge(nonTransitTravelTimesToDestinations, pointSetTimes);
            }
        }

        // Handle park+ride, a mode represented in the request LegMode but not in the internal StreetMode.
        // FIXME this special case for CAR_PARK currently overwrites any results from other access modes.
        //       That means computation is completely wasted and maybe duplicated.
        // This is pretty ugly, and should be integrated into the mode loop above.
        if (request.accessModes.contains(LegMode.CAR_PARK)) {
            // Currently first search from origin to P+R is hardcoded as time dominance variable for Max car time seconds
            // Second search from P+R to stops is not actually a search we just return list of all reached stops for each found P+R.
            // If multiple P+Rs reach the same stop, only one with shortest time is returned. Stops were searched for during graph building phase.
            // time to stop is time from CAR streetrouter to stop + CAR PARK time + time to walk to stop based on request walk speed
            // by default 20 CAR PARKS are found it can be changed with sr.maxVertices variable
            // FIXME we should not limit the number of car parks found.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.profileRequest = request;
            sr = PointToPointQuery.findParkRidePath(request, sr, network.transitLayer);
            if (sr == null) {
                // Origin not found. Signal this using the same flag as the other modes do.
                foundAnyOriginPoint = false;
            } else {
                bestAccessOptions.update(sr.getReachedStops(), StreetMode.CAR);
                foundAnyOriginPoint = true;
            }
            // Disallow non-transit access.
            // TODO should we allow non transit access with park and ride? Maybe with an additional parameter?
            nonTransitTravelTimesToDestinations = PointSetTimes.allUnreached(destinations);
        }

        if (!foundAnyOriginPoint) {
            // The origin point was not even linked to the street network.
            // Calling finish() before streaming in any travel times to destinations is designed to produce the right result.
            LOG.info("Origin point was outside the street network. Skipping routing and propagation, and returning default result.");
            return travelTimeReducer.finish();
        }

        // Short circuit unnecessary transit routing: If the origin was linked to a road, but no transit stations
        // were reached, return the non-transit grid as the final result.
        if (request.transitModes.isEmpty() || bestAccessOptions.streetTimesAndModes.isEmpty()) {
            LOG.info("Skipping transit search. No transit stops were reached or no transit modes were selected.");
            int nTargets =  nonTransitTravelTimesToDestinations.size();
            if (request instanceof RegionalTask && ((RegionalTask) request).oneToOne) nTargets = 1;
            for (int target = 0; target < nTargets; target++) {
                // TODO: pull this loop out into a method: travelTimeReducer.recordPointSetTimes(accessTimes)
                final int travelTimeSeconds = nonTransitTravelTimesToDestinations.getTravelTimeToPoint(target);
                travelTimeReducer.recordUnvaryingTravelTimeAtTarget(target, travelTimeSeconds);
            }
            return travelTimeReducer.finish();
        }

        // II. Transit Routing ========================================================================================
        // Transit stops were reached. Perform transit routing from those stops to all other reachable stops. The result
        // is a travel time in seconds for each iteration (departure time x monte carlo draw), for each transit stop.
        int[][] transitTravelTimesToStops;
        FastRaptorWorker worker = null;
        if (request.inRoutingFareCalculator == null) {
            worker = new FastRaptorWorker(network.transitLayer, request, bestAccessOptions.getTimes());
            if (request.includePathResults || request.makeTauiSite) {
                // By default, this is false and intermediate results (e.g. paths) are discarded.
                // TODO do we really need to save all states just to get the travel time breakdown?
                worker.retainPaths = true;
            }
            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
            // Additional detailed path information is retained in the FastRaptorWorker after routing.
            transitTravelTimesToStops = worker.route();
        } else {
            // TODO maxClockTime could provide a tighter bound, as it could be based on the actual departure time, not the last possible
            IntFunction<DominatingList> listSupplier =
                    (departureTime) -> new FareDominatingList(
                            request.inRoutingFareCalculator,
                            request.maxFare,
                            departureTime + request.maxTripDurationMinutes * FastRaptorWorker.SECONDS_PER_MINUTE);
            McRaptorSuboptimalPathProfileRouter mcRaptorWorker = new McRaptorSuboptimalPathProfileRouter(network,
                    request, null, null, listSupplier, InRoutingFareCalculator.getCollator(request));
            mcRaptorWorker.route();
            transitTravelTimesToStops = mcRaptorWorker.getBestTimes();
        }

        // III. Egress Propagation ======================================================================================
        // Propagate these travel times for every iteration at every stop out to the destination points, via streets.

        // Prepare a set of modes, all of which will simultaneously be used for on-street egress.
        EnumSet<StreetMode> egressStreetModes = LegMode.toStreetModeSet(request.egressModes);

        // This propagator will link the destinations to the street layer for all modes as needed.
        PerTargetPropagater perTargetPropagater = new PerTargetPropagater(
                destinations,
                network.streetLayer,
                egressStreetModes,
                request,
                transitTravelTimesToStops,
                nonTransitTravelTimesToDestinations.travelTimes
        );

        // We cannot yet merge the functionality of the TravelTimeReducer into the PerTargetPropagator
        // because in the non-transit case we call the reducer directly (see above).
        perTargetPropagater.travelTimeReducer = travelTimeReducer;

        // When path results are needed (directly requested, or for a Taui site), read them from the worker,
        // annotating with the access mode, then use the annotated paths to initialize the appropriate field in the
        // propagater. Not supported for fare requests, which use the McRaptor router and path style.
        if ((request.includePathResults || request.makeTauiSite) && worker != null) {
            perTargetPropagater.pathsToStopsForIteration = worker.pathsPerIteration.stream().peek(paths -> {
                for (Path path : paths) {
                    if (path != null) {
                        path.patternSequence.stopSequence.setAccess(bestAccessOptions);
                    }
                }
            }).collect(Collectors.toList());
            // Initialize the propagater's pathWriter to write Taui results directly to storage (instead of returning
            // them to the backend).
            if (request.makeTauiSite) {
                perTargetPropagater.pathWriter = new PathWriter(request);
            }
        }

        return perTargetPropagater.propagate();

    }

}
