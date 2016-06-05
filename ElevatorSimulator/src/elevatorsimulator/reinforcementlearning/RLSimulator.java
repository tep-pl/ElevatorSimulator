package elevatorsimulator.reinforcementlearning;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import elevatorsimulator.Building;
import elevatorsimulator.Scenarios;
import elevatorsimulator.SchedulerCreator;
import elevatorsimulator.SchedulingAlgorithm;
import elevatorsimulator.Simulator;
import elevatorsimulator.SimulatorClock;
import elevatorsimulator.SimulatorRunner;
import elevatorsimulator.SimulatorSettings;
import elevatorsimulator.SimulatorStats;
import elevatorsimulator.StatsInterval;
import elevatorsimulator.reinforcementlearning.ElevatorSystemAgent.Action;
import elevatorsimulator.schedulers.LongestQueueFirst;
import elevatorsimulator.schedulers.ReinforcementLearning;
import elevatorsimulator.schedulers.RoundRobin;
import elevatorsimulator.schedulers.ThreePassageGroupElevator;
import elevatorsimulator.schedulers.Zoning;
import marl.agents.learning.qlearning.DiscreteQTable;
import marl.utility.Config;
import marl.utility.Rand;

/**
 * Represents a simulator that uses reinforcement learning
 * @author Anton Jansson and Kristoffer Uggla Lingvall
 *
 */
public class RLSimulator {	
	private static class HourUsage {
		public final double[] usage = new double[ElevatorSystemAgent.Action.values().length];
	}
	
	/**
	 * Exports the scheduler usage
	 * @param simulationName The name of the simulation
	 * @param schedulerUsage The scheduler usage
	 */
	private static void exportSchedulerUsage(String simulationName, List<List<HourUsage>> schedulerUsage) {
		int minUsageHours = Integer.MAX_VALUE;
		for (List<HourUsage> dataHourUsage : schedulerUsage) {
			minUsageHours = Math.min(minUsageHours, dataHourUsage.size());
		}	
		
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter("data/" + simulationName + "-SchedulerUsage.csv"));
			writer.write("Hour");
			for (Action action : ElevatorSystemAgent.Action.values()) {
				writer.write(";" + action);
			}
			writer.write("\n");
			
			for (int i = 0; i < minUsageHours; i++) {
				HourUsage averageHourUsage = new HourUsage();
				for (List<HourUsage> runUsage : schedulerUsage) {
					HourUsage currentHour = runUsage.get(i);
					
					for (int action = 0; action < currentHour.usage.length; action++) {
						averageHourUsage.usage[action] += currentHour.usage[action] / schedulerUsage.size();
					}
				}		
				
				writer.write(i + ";");
				
				for (double actionUsage : averageHourUsage.usage) {
					writer.write(actionUsage + ";");
				}
				
				writer.write("\n");
			}
						
			writer.flush();
			writer.close();
		} catch (IOException e) {
		
		}
	}
	
	/**
	 * Exports the average squared wait time
	 * @param simulationName The simulation name
	 * @param aswt The average squared wait time
	 */
	private static void exportAverageSquaredWaitTime(String simulationName, List<Double> aswt) {
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter("data/" + simulationName + "-LearningASWT.csv"));
			writer.write("Episode;");
			writer.write("Average squared wait time");
			writer.write("\n");
			
			for (int i = 0; i < aswt.size(); i++) {
				writer.write(i + ";");
				writer.write(aswt.get(i) + "");
				writer.write("\n");
			}
						
			writer.flush();
			writer.close();
		} catch (IOException e) {
		
		}
	}
	
	public static void main(String[] args) throws IOException {
		Config config = new Config();
		String projectPath = Paths.get("").toAbsolutePath().toString();
		config.readFile(projectPath + "/ElevatorSimulator/src/elevatorsimulator/reinforcementlearning/config.ini");
		
		if (config.getInt("rand_seed") == -1) {
			Rand.INSTANCE.setSeed(System.currentTimeMillis());
		} else {
			Rand.INSTANCE.setSeed((long)config.getInt("rand_seed"));
		}
		
		DiscreteQTable.setInitialValue(-1000);
		
		//Create the simulator
		SchedulerCreator creator = new SchedulerCreator() {		
			@Override
			public SchedulingAlgorithm createScheduler(Building building) {
				List<SchedulingAlgorithm> schedulers = new ArrayList<SchedulingAlgorithm>();
				schedulers.add(new LongestQueueFirst());
				schedulers.add(new Zoning(building.getElevatorCars().length, building));
				schedulers.add(new RoundRobin(building, false));
				schedulers.add(new ThreePassageGroupElevator(building));
				schedulers.add(new RoundRobin(building, true));
				return new ReinforcementLearning(schedulers);
			}
		};
			    
	    // Obtain from the configuration how to run the experiment
	    int maxEpisodes = config.getInt("max_episodes");
	    double intervalLearningLength = 10 * 60;
	    		
	    // Create the agent 
	    ElevatorSystemAgent agent = new ElevatorSystemAgent(config);

	    Random seedGenerator = new Random(SimulatorRunner.DATA_RUN_SEED);
		int dataRuns = SimulatorRunner.NUM_DATA_RUNS;
		
		long[] randSeeds = new long[dataRuns];
		for (int i = 0; i < dataRuns; i++) {
			randSeeds[i] = seedGenerator.nextLong();
		}	
		
		//Increase the number of episodes so we get #trained episodes=maxEpisodes
		maxEpisodes += dataRuns;
	    
	    System.out.println("Starting Experiment");
	    long start = System.currentTimeMillis();
	    
        agent.initialise();
        	     
        //Statistics
        List<StatsInterval> globalStats = new ArrayList<StatsInterval>();
        List<List<StatsInterval>> hourStats = new ArrayList<List<StatsInterval>>();
        List<List<HourUsage>> schedulerUsage = new ArrayList<List<HourUsage>>();	
        List<Double> aswtStats = new ArrayList<Double>();
        
        String simulationName = "";
        
        for (int episodeNo = 0; episodeNo < maxEpisodes; episodeNo++) {
            boolean isDataRun = episodeNo >= maxEpisodes - dataRuns;
            
            long seed = -1;
            
            //Check if data run
            if (isDataRun) {
            	seed = randSeeds[dataRuns - (maxEpisodes - episodeNo)];
            	agent.evaluationMode(true); //This will make the agent follow the policy.
            }
            
    		Simulator simulator = new Simulator(
				Scenarios.createLargeBuilding(3),
				new SimulatorSettings(0.01, 24 * 60 * 60),
				creator,
				seed);
    		
    	    ElevatorSystemEnvironment env = new ElevatorSystemEnvironment(simulator);   
    	    
            // Initialize the environment and agent
            env.initialise();
            
            // Add the agent into the environment
            env.add(agent);
            
            // Reset the environment
            env.reset(episodeNo);
                             		
            simulator.start();
            
            if (simulationName == "") {
        		simulationName = simulator.getSimulationName() + "-" + (maxEpisodes - dataRuns);
            }
            
            long lastInterval = 0;
            SimulatorClock clock = simulator.getClock();
            List<Long> exited = new ArrayList<Long>();
                      
            //For the first interval
            agent.getActionUsage().add(ElevatorSystemAgent.Action.LONGEST_QUEUE_FIRST);
            
            while (simulator.advance()) {
            	if (clock.elapsedSinceRealTime(lastInterval) >= clock.secondsToTime(intervalLearningLength)) {
	            	env.incrementTime();
	            	exited.add(simulator.getStats().getPollInterval().getNumExists());
	            	simulator.getStats().resetPollInterval();
	            	lastInterval = clock.timeNow();
            	}
            }          
            
            //For the last interval
            env.rewardLastState();
            exited.add(simulator.getStats().getPollInterval().getNumExists());
            
            if (isDataRun) {
            	globalStats.add(simulator.getStats().getGlobalInterval());
            	hourStats.add(simulator.getStats().getStatsIntervals());
            }
            
            System.out.println(
            	"\tEpisode #" + (episodeNo + 1)
            	+ " Reward: " + env.totalReward() + " Average SWT: " + simulator.getStats().averageSquaredWaitTime() + "s"
            	+ " State space: " + agent.getStateSpace());
            
            aswtStats.add(simulator.getStats().averageSquaredWaitTime());
            
            for (int i = 0; i < agent.getActionDistribution().length; i++) {
        		System.out.println("\t" + ElevatorSystemAgent.Action.values()[i] + ": " + agent.getActionDistribution()[i]);
        	}
            
            if (episodeNo == maxEpisodes - 1) {
	            System.out.print("\t0: ");
	            int i = 0;
	            int count = 0;
	            int hour = 0;
	            for (ElevatorSystemAgent.Action action : agent.getActionUsage()) {
	            	System.out.print(action.toString().charAt(0) + ": " + exited.get(i) + " ");
	            	count++;
	            	i++;
	            	
	            	if (count == 6) {
	            		hour++;
	            		System.out.println();
	            		System.out.print("\t" + hour + ": ");
	            		count = 0;
	            	}
	            }
            }
            	                      
            if (isDataRun) {
	            int count = 0;
	            HourUsage hourUsage = new HourUsage();
	            List<HourUsage> dataRunUsage = new ArrayList<RLSimulator.HourUsage>();
	            
	            for (ElevatorSystemAgent.Action action : agent.getActionUsage()) {
	            	hourUsage.usage[action.ordinal()] += 1;
	            	count++;
	            	
	            	if (count == 6) {
	            		dataRunUsage.add(hourUsage);
	            		hourUsage = new HourUsage();
	            		count = 0;
	            	}
	            }
	            
	            dataRunUsage.add(hourUsage);         
	            schedulerUsage.add(dataRunUsage);
            }
            
            System.out.println();
        }
	    
	    System.out.println();
	    System.out.println("-- End of Experiment--");
	    long end = System.currentTimeMillis();
	    System.out.println("Experiment ran for " + (end - start) + "ms");	
	    
	    //Export statistics
		List<StatsInterval> averageStats = new ArrayList<StatsInterval>();
		averageStats.add(StatsInterval.average(globalStats));
		StatsInterval.exportStats(simulationName, averageStats, SimulatorStats.INTERVAL_LENGTH_SEC);
		
		List<StatsInterval> averageHourStats = StatsInterval.averageHours(hourStats);			
		StatsInterval.exportStats(simulationName + "-Hour", averageHourStats, SimulatorStats.INTERVAL_LENGTH_SEC);
		
		exportSchedulerUsage(simulationName, schedulerUsage);
		exportAverageSquaredWaitTime(simulationName, aswtStats);
	}
}
