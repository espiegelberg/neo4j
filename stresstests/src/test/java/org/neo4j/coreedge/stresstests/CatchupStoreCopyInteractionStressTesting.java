/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.stresstests;

import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.neo4j.coreedge.discovery.Cluster;
import org.neo4j.coreedge.discovery.HazelcastDiscoveryServiceFactory;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_0;

import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.lang.System.getProperty;
import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertTrue;
import static org.neo4j.StressTestingHelper.ensureExistsAndEmpty;
import static org.neo4j.StressTestingHelper.fromEnv;
import static org.neo4j.coreedge.stresstests.ClusterConfiguration.configureRaftLogRotationAndPruning;
import static org.neo4j.coreedge.stresstests.ClusterConfiguration.configureTxLogRotationAndPruning;
import static org.neo4j.function.Suppliers.untilTimeExpired;

public class CatchupStoreCopyInteractionStressTesting
{
    private static final String DEFAULT_NUMBER_OF_CORES = "3";
    private static final String DEFAULT_NUMBER_OF_EDGES = "1";
    private static final String DEFAULT_DURATION_IN_MINUTES = "30";
    private static final String DEFAULT_WORKING_DIR = new File( getProperty( "java.io.tmpdir" ) ).getPath();

    @Test
    public void shouldBehaveCorrectlyUnderStress() throws Exception
    {
        int numberOfCores =
                parseInt( fromEnv( "CATCHUP_STORE_COPY_INTERACTION_STRESS_NUMBER_OF_CORES", DEFAULT_NUMBER_OF_CORES ) );
        int numberOfEdges =
                parseInt( fromEnv( "CATCHUP_STORE_COPY_INTERACTION_STRESS_NUMBER_OF_EDGES", DEFAULT_NUMBER_OF_EDGES ) );
        long durationInMinutes =
                parseLong( fromEnv( "CATCHUP_STORE_COPY_INTERACTION_STRESS_DURATION", DEFAULT_DURATION_IN_MINUTES ) );
        String workingDirectory =
                fromEnv( "CATCHUP_STORE_COPY_INTERACTION_STRESS_WORKING_DIRECTORY", DEFAULT_WORKING_DIR );

        File clusterDirectory = ensureExistsAndEmpty( new File( workingDirectory, "cluster" ) );

        Map<String,String> coreParams =
                configureRaftLogRotationAndPruning( configureTxLogRotationAndPruning( new HashMap<>() ) );
        Map<String,String> edgeParams = configureTxLogRotationAndPruning( new HashMap<>() );

        HazelcastDiscoveryServiceFactory discoveryServiceFactory = new HazelcastDiscoveryServiceFactory();
        Cluster cluster =
                new Cluster( clusterDirectory, numberOfCores, numberOfEdges, discoveryServiceFactory, coreParams,
                        emptyMap(), edgeParams, emptyMap(), StandardV3_0.NAME );

        AtomicBoolean stopTheWorld = new AtomicBoolean();
        BooleanSupplier keepGoing =
                () -> !stopTheWorld.get() && untilTimeExpired( durationInMinutes, TimeUnit.MINUTES ).getAsBoolean();
        Runnable onFailure = () -> stopTheWorld.set( true );

        ExecutorService service = Executors.newFixedThreadPool( 3 );
        try
        {
            cluster.start();
            Future<Boolean> workload = service.submit( new Workload( keepGoing, onFailure, cluster ) );
            Future<Boolean> startStopWorker = service.submit( new StartStopLoad( keepGoing, onFailure, cluster ) );
            Future<Boolean> catchUpWorker = service.submit( new CatchUpLoad( keepGoing, onFailure, cluster ) );

            assertTrue( workload.get() );
            assertTrue( startStopWorker.get() );
            assertTrue( catchUpWorker.get() );
        }
        finally
        {
            cluster.shutdown();
        }

        // let's cleanup disk space when everything went well
        FileUtils.deleteRecursively( clusterDirectory );
    }
}
