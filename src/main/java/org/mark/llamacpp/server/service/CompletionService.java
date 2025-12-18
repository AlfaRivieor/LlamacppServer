package org.mark.llamacpp.server.service;

import java.util.List;

import org.mark.llamacpp.server.struct.CharactorDataStruct;

/**
 * 	用来搞本地RP的。
 */
public class CompletionService {
	
	public CompletionService() {
		
	}
	
	/**
	 * 	
	 * @return
	 */
	public synchronized CharactorDataStruct createDefaultCharactor() {
		CharactorDataStruct charactorDataStruct = new CharactorDataStruct();
		
		charactorDataStruct.setId(System.currentTimeMillis());
		charactorDataStruct.setCreatedAt(System.currentTimeMillis());
		charactorDataStruct.setPrompt("");
		charactorDataStruct.setSystemPrompt("");
		charactorDataStruct.setTitle("默认角色-" + System.currentTimeMillis());
		charactorDataStruct.setUpdatedAt(System.currentTimeMillis());
		// 写入本地磁盘
		this.saveCharactor(charactorDataStruct);
		return charactorDataStruct;
	}
	
	
	/**
	 * 	保存到本地文件。
	 * @param charactorDataStruct
	 */
	public void saveCharactor(CharactorDataStruct charactorDataStruct) {
		// TODO
		
		
	}
	
	/**
	 * 	列出全部的角色
	 * @return
	 */
	public List<CharactorDataStruct> listCharactor() {
		// TODO
		return null;
	}
	
	
	/**
	 * 	查询指定角色的聊天记录
	 * @param charactorDataStruct
	 * @return
	 */
	public String queryCharactorLog(CharactorDataStruct charactorDataStruct) {
		
		
		return null;
	}
	
}
