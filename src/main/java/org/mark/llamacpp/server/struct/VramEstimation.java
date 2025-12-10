package org.mark.llamacpp.server.struct;



public class VramEstimation {
	
	/**
	 * 	总计估算值
	 */
	private long totalVramRequired = 0;
	
	
	/**
	 * 	模型权重
	 */
	private long modelWeights = 0;
	
	/**
	 * 	KV
	 */
	private long kvCache = 0;
	
	/**
	 * 	忘了
	 */
	private long runtimeOverhead = 0;
	
	
	public VramEstimation() {
		
	}
	
	public VramEstimation(long totalVramRequired, long modelWeights, long kvCache, long runtimeOverhead) {
		this.totalVramRequired = totalVramRequired;
		this.modelWeights = modelWeights;
		this.kvCache = kvCache;
		this.runtimeOverhead = runtimeOverhead;
	}

	public long getTotalVramRequired() {
		return totalVramRequired;
	}

	public void setTotalVramRequired(long totalVramRequired) {
		this.totalVramRequired = totalVramRequired;
	}

	public long getModelWeights() {
		return modelWeights;
	}

	public void setModelWeights(long modelWeights) {
		this.modelWeights = modelWeights;
	}

	public long getKvCache() {
		return kvCache;
	}

	public void setKvCache(long kvCache) {
		this.kvCache = kvCache;
	}

	public long getRuntimeOverhead() {
		return runtimeOverhead;
	}

	public void setRuntimeOverhead(long runtimeOverhead) {
		this.runtimeOverhead = runtimeOverhead;
	}
}
