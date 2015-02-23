/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder.module;

import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.graph_builder.annotation.BikeParkUnlinked;
import org.opentripplanner.graph_builder.annotation.BikeRentalStationUnlinked;
import org.opentripplanner.graph_builder.annotation.StopUnlinked;

import org.opentripplanner.graph_builder.services.GraphBuilderModule;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetTransitLink;
import org.opentripplanner.routing.edgetype.loader.NetworkLinkerLibrary;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.BikeParkVertex;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link org.opentripplanner.graph_builder.services.GraphBuilderModule} plugin that links up the stops of a transit network to a street network.
 * Should be called after both the transit network and street network are loaded.
 */
public class TransitToStreetNetworkModule implements GraphBuilderModule {

    private static final Logger LOG = LoggerFactory.getLogger(TransitToStreetNetworkModule.class);

    private NetworkLinkerLibrary networkLinkerLibrary;

    private Graph graph;

    public List<String> provides() {
        return Arrays.asList("street to transit", "linking");
    }

    public List<String> getPrerequisites() {
        return Arrays.asList("streets"); // why not "transit" ?
    }

    @Override
    public void buildGraph(Graph graph, HashMap<Class<?>, Object> extra) {
        LOG.info("Linking transit stops to streets...");
        this.networkLinkerLibrary = new NetworkLinkerLibrary(graph, extra);
        this.graph = graph;
        if(graph.hasStreets && graph.hasTransit) {
            linkTransit();
            linkBikeRentalStations();
            linkParkRideStations();
            cleanGraph();
        }
    }

    /**
     * Links transit stops to Streets
     */
    private void linkTransit() {
        // iterate over a copy of vertex list because it will be modified
        ArrayList<Vertex> vertices = new ArrayList<Vertex>();
        vertices.addAll(graph.getVertices());

        int nUnlinked = 0;
        for (TransitStop ts : Iterables.filter(vertices, TransitStop.class)) {
            // if the street is already linked there is no need to linked it again,
            // could happened if using the prune isolated island
            boolean alreadyLinked = false;
            for(Edge e:ts.getOutgoing()){
                if(e instanceof StreetTransitLink) {
                    alreadyLinked = true;
                    break;
                }
            }
            if(alreadyLinked) continue;
            // only connect transit stops that (a) are entrances, or (b) have no associated
            // entrances
            if (ts.isEntrance() || !ts.hasEntrances()) {
                boolean wheelchairAccessible = ts.hasWheelchairEntrance();
                if (!networkLinkerLibrary.connectVertexToStreets(ts, wheelchairAccessible).getResult()) {
                    LOG.debug(graph.addBuilderAnnotation(new StopUnlinked(ts)));
                    nUnlinked += 1;
                }
            }
        }
        if (nUnlinked > 0) {
            LOG.warn("{} transit stops were not close enough to the street network to be connected to it.", nUnlinked);
        }
    }

    /*
     * TODO Those two steps should be in a separate builder, really. We re-use this builder to
     * prevent having to spatially re-index several times the street network. Instead we could
     * have a "spatial indexer" builder that add a spatial index to the graph, and make all
     * builders that rely on spatial indexing to add a dependency to this builder. And we do not
     * link stations directly in the OSM build as they can come from other builders (static bike
     * rental or P+R builders) and street data can be coming from shapefiles.
     */
    /**
     * Links Bike rental vertices to streets
     */
    private void linkBikeRentalStations() {
        //TODO: copy only BikeRentalVertex vertices
        // iterate over a copy of vertex list because it will be modified
        ArrayList<Vertex> vertices = new ArrayList<Vertex>();
        vertices.addAll(graph.getVertices());

        LOG.debug("Linking bike rental stations...");
        for (BikeRentalStationVertex brsv : Iterables.filter(vertices,
                BikeRentalStationVertex.class)) {
            if (!networkLinkerLibrary.connectVertexToStreets(brsv).getResult()) {
                LOG.warn(graph.addBuilderAnnotation(new BikeRentalStationUnlinked(brsv)));
            }
        }
    }

    /**
     * Links P+R stations to streets
     */
    private void linkParkRideStations() {
        //TODO: copy only BikeParkVertex vertices
        // iterate over a copy of vertex list because it will be modified
        ArrayList<Vertex> vertices = new ArrayList<Vertex>();
        vertices.addAll(graph.getVertices());

        LOG.debug("Linking bike P+R stations...");
        for (BikeParkVertex bprv : Iterables.filter(vertices, BikeParkVertex.class)) {
            if (!networkLinkerLibrary.connectVertexToStreets(bprv).getResult()) {
                LOG.warn(graph.addBuilderAnnotation(new BikeParkUnlinked(bprv)));
            }
        }
    }

    /**
     * Removes split edges and replaced it with split edges which were split
     * when linking transit/bike rent/P+R
     */
    private void cleanGraph() {
        //remove replaced edges
        for (HashSet<StreetEdge> toRemove : networkLinkerLibrary.getReplacements().keySet()) {
            for (StreetEdge edge : toRemove) {
                edge.getFromVertex().removeOutgoing(edge);
                edge.getToVertex().removeIncoming(edge);
            }
        }
        //and add back in replacements
        for (LinkedList<P2<StreetEdge>> toAdd : networkLinkerLibrary.getReplacements().values()) {
            for (P2<StreetEdge> edges : toAdd) {
                StreetEdge edge1 = edges.first;
                if (edge1.getToVertex().getLabel().startsWith("split ") || edge1.getFromVertex().getLabel().startsWith("split ")) {
                    continue;
                }
                edge1.getFromVertex().addOutgoing(edge1);
                edge1.getToVertex().addIncoming(edge1);
                StreetEdge edge2 = edges.second;
                if (edge2 != null) {
                    edge2.getFromVertex().addOutgoing(edge2);
                    edge2.getToVertex().addIncoming(edge2);
                }
            }
        }
    }

    @Override
    public void checkInputs() {
        //no inputs
    }
}
