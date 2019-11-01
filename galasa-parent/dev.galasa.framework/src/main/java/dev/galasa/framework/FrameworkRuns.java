/*
 * Licensed Materials - Property of IBM
 * 
 * (c) Copyright IBM Corp. 2019.
 */
package dev.galasa.framework;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dev.galasa.framework.spi.AbstractManager;
import dev.galasa.framework.spi.DynamicStatusStoreException;
import dev.galasa.framework.spi.FrameworkException;
import dev.galasa.framework.spi.IConfigurationPropertyStoreService;
import dev.galasa.framework.spi.IDynamicStatusStoreService;
import dev.galasa.framework.spi.IFramework;
import dev.galasa.framework.spi.IFrameworkRuns;
import dev.galasa.framework.spi.IRun;

public class FrameworkRuns implements IFrameworkRuns {

	private final static Log logger = LogFactory.getLog(FrameworkRuns.class);

	private final Pattern  runPattern = Pattern.compile("^\\Qrun.\\E(\\w+)\\Q.\\E.*$");

	private final IFramework                         framework;
	private final IDynamicStatusStoreService         dss;
	private final IConfigurationPropertyStoreService cps;
	
	private final String NO_GROUP     = "none";
	private final String NO_RUNTYPE   = "unknown";
	private final String NO_REQUESTER = "unknown";
	
	private final String RUN_PREFIX   = "run.";
	
	
	public FrameworkRuns(Framework framework) throws FrameworkException {
		this.framework = framework;
		this.dss       = framework.getDynamicStatusStoreService("framework");
		this.cps       = framework.getConfigurationPropertyService("framework");
	}

	@Override
	public List<IRun> getActiveRuns() throws FrameworkException {

		List<IRun> runs = getAllRuns();
		Iterator<IRun> iruns = runs.iterator();
		while(iruns.hasNext()) {
			IRun run = iruns.next();

			if (run.getHeartbeat() != null) {
				continue;
			}

			if ("allocated".equals(run.getStatus())) {
				continue;
			}

			iruns.remove();
		}

		return runs;
	}

	@Override
	public @NotNull List<IRun> getQueuedRuns() throws FrameworkException {
		List<IRun> runs = getAllRuns();
		Iterator<IRun> iruns = runs.iterator();
		while(iruns.hasNext()) {
			IRun run = iruns.next();

			if (!"queued".equals(run.getStatus())) {
				iruns.remove();
			}
		}

		return runs;
	}
	
	@Override
	public List<IRun> getAllRuns() throws FrameworkException {
		HashMap<String, IRun> runs = new HashMap<>();

		logger.trace("Fetching all runs from DSS");
		Map<String, String> runProperties = dss.getPrefix(RUN_PREFIX);
		logger.trace("Fetched all runs from DSS");
		for(String key : runProperties.keySet()) {
			Matcher matcher = runPattern.matcher(key);
			if (matcher.find()) {
				String runName = matcher.group(1);

				if (!runs.containsKey(runName)) {
					runs.put(runName, new RunImpl(runName, this.dss));
				}
			}
		}

		LinkedList<IRun> returnRuns = new LinkedList<>(runs.values());

		return returnRuns;
	}
	
	@Override
	public List<IRun> getAllGroupedRuns(@NotNull String groupName) throws FrameworkException {
		List<IRun> allRuns = this.getAllRuns();
		List<IRun> groupedRuns = new LinkedList<IRun>();
		
		for(IRun run : allRuns) {
			if(groupName.equals(run.getGroup())) {
				groupedRuns.add(run);
			}
		}		
		return groupedRuns;
	}

	@Override
	public @NotNull Set<String> getActiveRunNames() throws FrameworkException {
		List<IRun> runs = getActiveRuns();

		HashSet<String> runNames = new HashSet<>();
		for(IRun run : runs) {
			runNames.add(run.getName());
		}

		return runNames;
	}

	@Override
	@NotNull
	public @NotNull IRun submitRun(String runType, 
			String requestor, 
			@NotNull String bundleName, 
			@NotNull String testName, 
			String groupName,
			String mavenRepository, 
			String obr,
			String stream,
			boolean local,
			boolean trace,
			Properties overrides)
					throws FrameworkException {
		if (testName == null) {
			throw new FrameworkException("Missing test name");
		}
		if (bundleName == null) {
			throw new FrameworkException("Missing bundle name");
		}

		String bundleTest = bundleName + "/" + testName;

		groupName = AbstractManager.nulled(groupName);
		if(groupName == null) {
			groupName = NO_GROUP;
		}
		runType = AbstractManager.nulled(runType);
		if (runType == null) {
			runType = NO_RUNTYPE;
		}
		requestor = AbstractManager.nulled(requestor);
		if (requestor == null) {
			requestor = AbstractManager.nulled(cps.getProperty("run", "requestor"));
			if (requestor == null) {
				requestor = "unknown";
			}
		}
		stream = AbstractManager.nulled(stream);


		String runName = null;

		//*** Allocate the next number for the run type

		//*** Get the prefix of this run type
		String typePrefix = AbstractManager.nulled(this.cps.getProperty("request.type." + runType,  "prefix"));
		if (typePrefix == null) {
			if ("local".equals(runType)) {
				typePrefix = "L";
			} else {
				typePrefix = "U"; //*** For unknown prefix
			}
		}

		//*** Get the maximum number for this prefix
		int maxNumber = Integer.MAX_VALUE;
		String sMaxNumber = AbstractManager.nulled(this.cps.getProperty("request.prefix", "maximum", typePrefix));
		if (sMaxNumber != null) {
			maxNumber = Integer.parseInt(sMaxNumber);
		}

		try {
			//*** Now loop until we find the next free number for this run type
			boolean maxlooped = false;
			while(runName == null) {
				String pLastused = "request.prefix." + typePrefix + ".lastused";
				String sLatestNumber = this.dss.get(pLastused);
				int latestNumber = 0;
				if (sLatestNumber != null && !sLatestNumber.trim().isEmpty()) {
					latestNumber = Integer.parseInt(sLatestNumber);
				}

				//*** Add 1 to the run number and see if we get it
				latestNumber++;
				if (latestNumber > maxNumber) { //*** have we gone past the maximum number
					if (maxlooped) {
						throw new FrameworkException("Not enough request type numbers available, looped twice");
					}
					latestNumber = 1;
					maxlooped = true; //*** Safety check to make sure we havent gone through all the numbers again
				}

				String sNewNumber = Integer.toString(latestNumber);
				if (!this.dss.putSwap(pLastused, sLatestNumber, sNewNumber)) {
					Thread.sleep(this.framework.getRandom().nextInt(200)); //*** Wait for a bit, to avoid race conditions
					continue;    //  Try again with the new latest number
				}

				String tempRunName = typePrefix + sNewNumber;

				//*** Set up the otherRunProperties that will go with the Run number
				HashMap<String, String> otherRunProperties = new HashMap<>();
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".status", "queued");
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".queued", Instant.now().toString());
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".testbundle", bundleName);
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".testclass", testName);
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".request.type", runType);
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".local", Boolean.toString(local));
				if (trace) {
					otherRunProperties.put(RUN_PREFIX + tempRunName + ".trace", "true");
				}
				if (mavenRepository != null) {
					otherRunProperties.put(RUN_PREFIX + tempRunName + ".repository", mavenRepository);
				}
				if (obr != null) {
					otherRunProperties.put(RUN_PREFIX + tempRunName + ".obr", obr);
				}
				if (stream != null) {
					otherRunProperties.put(RUN_PREFIX + tempRunName + ".stream", stream);
				}
				if (groupName != null) {
					otherRunProperties.put(RUN_PREFIX + tempRunName + ".group", groupName);
				}else {
					otherRunProperties.put(RUN_PREFIX + tempRunName + ".group", UUID.randomUUID().toString());
				}
				otherRunProperties.put(RUN_PREFIX + tempRunName + ".requestor", requestor.toLowerCase());
				
				//*** Add in the overrides
				if (overrides != null) {
					for(java.util.Map.Entry<Object, Object> entry : overrides.entrySet()) {
						String key = (String)entry.getKey();
						String value = (String)entry.getValue();
						
						otherRunProperties.put(RUN_PREFIX + tempRunName + ".override." + key, value);					
					}
				}

				//*** See if we can setup the runnumber properties (clashes possible if low max number or sharing prefix
				if (!this.dss.putSwap(RUN_PREFIX + tempRunName + ".test", null, bundleTest, otherRunProperties)) {
					Thread.sleep(this.framework.getRandom().nextInt(200)); //*** Wait for a bit, to avoid race conditions
					continue; //*** Try again
				}

				runName = tempRunName; //*** Got it					
			}
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FrameworkException("Interrupted", e);
		} catch (Exception e) {
			throw new FrameworkException("Problem submitting job", e);
		}

		return new RunImpl(runName, this.dss);
	}

	@Override
	public boolean delete(String runname) throws DynamicStatusStoreException {
		String prefix = RUN_PREFIX + runname + ".";

		 Map<String, String> properties = this.dss.getPrefix(prefix);
		if (properties.isEmpty()) {
			return false;
		}

		this.dss.deletePrefix(prefix);
		return true;
	}

	@Override
	public boolean reset(String runname) throws DynamicStatusStoreException {
		String prefix = RUN_PREFIX + runname + ".";

		Map<String, String> properties = this.dss.getPrefix(prefix);
		if (properties.isEmpty()) {
			return false;
		}

		if ("true".equals(properties.get(prefix + "local"))) {
			return false;
		}

		this.dss.delete(prefix + "heartbeat");
		this.dss.put(prefix + "status", "queued");
		return true;
	}

	@Override
	public IRun getRun(String runname) throws DynamicStatusStoreException {
		String prefix = RUN_PREFIX + runname + ".";

		Map<String, String> properties = this.dss.getPrefix(prefix);
		if (properties.isEmpty()) {
			return null;
		}

		return new RunImpl(runname, this.dss);
	}



}
