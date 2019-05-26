package io.ejat.framework;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.ejat.framework.spi.DynamicStatusStoreException;
import io.ejat.framework.spi.IDynamicStatusStoreService;
import io.ejat.framework.spi.IRun;

public class RunImpl implements IRun {
	
	private final String name;
	private final Instant heartbeat;
	private final String type;
	private final String group;
	private final String test;
	private final String bundleName;
	private final String testName;
	private final String status;
	private final Instant queued;
	private final String requestor;
	private final String stream;
	private final String repo;
	private final String obr;
	private final Boolean local;
	
	public RunImpl(String name, IDynamicStatusStoreService dss) throws DynamicStatusStoreException {
		this.name      = name;
		
		String prefix = "run." + name + ".";
		
		Map<String, String> runProperties = dss.getPrefix("run." + this.name);
		
		String sHeartbeat = runProperties.get(prefix + "heartbeat");
		if (sHeartbeat != null) {
			this.heartbeat = Instant.parse(sHeartbeat);
		} else {
			this.heartbeat = null;
		}
		
		type      = runProperties.get(prefix + "request.type");
		test      = runProperties.get(prefix + "test");
		status    = runProperties.get(prefix + "status");
		requestor = runProperties.get(prefix + "requestor");
		stream    = runProperties.get(prefix + "stream");
		repo      = runProperties.get(prefix + "repo");
		obr       = runProperties.get(prefix + "obr");
		local     = Boolean.parseBoolean(runProperties.get(prefix + "local"));
		
		String pGroup = runProperties.get(prefix + "group");
		if (pGroup != null) {
			this.group = pGroup;
		} else {
			this.group = UUID.randomUUID().toString();
		}
		
		String sQueued = runProperties.get(prefix + "queued");
		if (sQueued != null) {
			this.queued = Instant.parse(sQueued);
		} else {
			if ("queued".equals(this.status)) {
				this.queued = Instant.now();
			} else {
				this.queued = null;
			}
		}
		
		
		String[] split = test.split("/");
		this.bundleName = split[0];
		this.testName   = split[1];
	}

	@Override
	public String getName() {
		return this.name;
	}
	
	@Override
	public Instant getHeartbeat() {
		return this.heartbeat;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public String getTest() {
		return test;
	}

	@Override
	public String getStatus() {
		return status;
	}

	@Override
	public String getRequestor() {
		return requestor;
	}

	@Override
	public String getStream() {
		return stream;
	}

	@Override
	public String getTestBundleName() {
		return this.bundleName;
	}

	@Override
	public String getTestClassName() {
		return this.testName;
	}

	@Override
	public boolean isLocal() {
		return this.local;
	}

	@Override
	public String getGroup() {
		return this.group;
	}

	@Override
	public Instant getQueued() {
		return this.queued;
	}

	@Override
	public String getRepository() {
		return this.repo;
	}

	@Override
	public String getOBR() {
		return this.obr;
	}
	
}