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

package org.opentripplanner.graph_builder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.opentripplanner.graph_builder.services.GraphBuilderModule;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Graph.LoadLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This makes a Graph out of various inputs like GTFS and OSM.
 * It is modular: GraphBuilderModules are placed in a list and run in sequence.
 */
public class GraphBuilder implements Runnable {
    
    private static Logger LOG = LoggerFactory.getLogger(GraphBuilder.class);

    private List<GraphBuilderModule> _graphBuilderModules = new ArrayList<GraphBuilderModule>();

    private File graphFile;
    
    private boolean _alwaysRebuild = true;

    private List<RoutingRequest> _modeList;
    
    private String _baseGraph = null;
    
    private Graph graph = new Graph();

    /** Should the graph be serialized to disk after being created or not? */
    public boolean serializeGraph = true;

    public void addGraphBuilder(GraphBuilderModule loader) {
        _graphBuilderModules.add(loader);
    }

    public void setGraphBuilders(List<GraphBuilderModule> graphLoaders) {
        _graphBuilderModules = graphLoaders;
    }

    public void setAlwaysRebuild(boolean alwaysRebuild) {
        _alwaysRebuild = alwaysRebuild;
    }
    
    public void setBaseGraph(String baseGraph) {
        this._baseGraph = baseGraph;
        try {
            graph = Graph.load(new File(baseGraph), LoadLevel.FULL);
        } catch (Exception e) {
            throw new RuntimeException("error loading base graph");
        }
    }

    public void addMode(RoutingRequest mo) {
        _modeList.add(mo);
    }

    public void setModes(List<RoutingRequest> modeList) {
        _modeList = modeList;
    }
    
    public void setPath (String path) {
        graphFile = new File(path.concat("/Graph.obj"));
    }
    
    public void setPath (File path) {
        graphFile = new File(path, "Graph.obj");
    }

    public Graph getGraph() {
        return this.graph;
    }

    public void run() {
        /* Record how long it takes to build the graph, purely for informational purposes. */
        long startTime = System.currentTimeMillis();

        if (serializeGraph) {
        	
            if (graphFile == null) {
                throw new RuntimeException("graphBuilderTask has no attribute graphFile.");
            }

            if( graphFile.exists() && ! _alwaysRebuild) {
                LOG.info("graph already exists and alwaysRebuild=false => skipping graph build");
                return;
            }
        	
            try {
                if (!graphFile.getParentFile().exists()) {
                    if (!graphFile.getParentFile().mkdirs()) {
                        LOG.error("Failed to create directories for graph bundle at " + graphFile);
                    }
                }
                graphFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Cannot create or overwrite graph at path " + graphFile);
            }
        }

        // Check all graph builder inputs, and fail fast to avoid waiting until the build process advances.
        for (GraphBuilderModule builder : _graphBuilderModules) {
            builder.checkInputs();
        }
        
        HashMap<Class<?>, Object> extra = new HashMap<Class<?>, Object>();
        for (GraphBuilderModule load : _graphBuilderModules)
            load.buildGraph(graph, extra);

        graph.summarizeBuilderAnnotations();
        if (serializeGraph) {
            try {
                graph.save(graphFile);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        } else {
            LOG.info("Not saving graph to disk, as requested.");
        }

        long endTime = System.currentTimeMillis();
        LOG.info(String.format("Graph building took %.1f minutes.", (endTime - startTime) / 1000 / 60.0));
    }

}