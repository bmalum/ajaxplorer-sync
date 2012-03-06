package info.ajaxplorer.synchro;

import info.ajaxplorer.client.http.AjxpAPI;
import info.ajaxplorer.client.http.RestRequest;
import info.ajaxplorer.client.http.RestStateHolder;
import info.ajaxplorer.client.model.Node;
import info.ajaxplorer.client.model.Server;
import info.ajaxplorer.synchro.model.SyncChange;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.http.HttpEntity;
import org.json.JSONObject;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com.j256.ormlite.dao.CloseableIterator;
import com.j256.ormlite.dao.Dao;

public class SyncJob implements org.quartz.Job {

	public static Integer NODE_CHANGE_STATUS_FILE_CREATED = 2;
	public static Integer NODE_CHANGE_STATUS_FILE_DELETED = 4;
	public static Integer NODE_CHANGE_STATUS_MODIFIED = 8;
	public static Integer NODE_CHANGE_STATUS_DIR_CREATED = 16;
	public static Integer NODE_CHANGE_STATUS_DIR_DELETED = 32;
	
	public static Integer TASK_DO_NOTHING = 1;
	public static Integer TASK_REMOTE_REMOVE = 2;
	public static Integer TASK_REMOTE_PUT_CONTENT = 4;
	public static Integer TASK_REMOTE_MKDIR = 8;
	public static Integer TASK_LOCAL_REMOVE = 16;
	public static Integer TASK_LOCAL_MKDIR = 32;
	public static Integer TASK_LOCAL_GET_CONTENT = 64;
	
	public static Integer STATUS_TODO = 2;
	public static Integer STATUS_DONE = 4;
	public static Integer STATUS_ERROR = 8;
	public static Integer STATUS_CONFLICT = 16;
	public static Integer STATUS_PROGRESS = 32;
	
	Node currentRepository ;
	final Dao<Node, String> nodeDao;
	
	private String currentJobNodeID;
	
	
	@Override
	public void execute(JobExecutionContext ctx) throws JobExecutionException {
		try {
			currentJobNodeID = ctx.getMergedJobDataMap().getString("node-id");
			this.run();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	
	public SyncJob() throws URISyntaxException, Exception{
		
        nodeDao = Manager.getInstance().getNodeDao();		
		
	}
	
	public void run()  throws URISyntaxException, Exception{
		
		Manager.getInstance().updateSynchroState(true);
		
		currentRepository = Manager.getInstance().getSynchroNode(currentJobNodeID);		
		Server s = new Server(currentRepository.getParent());
		RestStateHolder.getInstance().setServer(s);		
		RestStateHolder.getInstance().setRepository(currentRepository);
		AjxpAPI.getInstance().setServer(s);		
		
		final File d = new File(currentRepository.getPropertyValue("target_folder"));

        Manager.getInstance().notifyUser(Manager.getMessage("job_running"), "Synchronizing " + d.getPath() + " against " + s.getUrl());

    	Dao<SyncChange,String> syncDao = Manager.getInstance().getSyncChangeDao();
    	List<SyncChange> previouslyRemaining = syncDao.queryForEq("jobId", "unique-job");
		Map<String, Object[]> previousChanges = SyncChange.syncChangesToTreeMap(previouslyRemaining);
		Map<String, Object[]> again = null;
		if(previousChanges.size() > 0){
			System.out.println("Getting sync from previous job");
			again = applyChanges(previousChanges, d);
			syncDao.delete(previouslyRemaining);
		}
		
		//List<Node> emptySnapshot = new ArrayList<Node>();
		List<Node> localSnapshot = new ArrayList<Node>();
		List<Node> remoteSnapshot = new ArrayList<Node>();
		Node localRootNode = loadRootAndSnapshot("local_snapshot", localSnapshot, d);
		Node remoteRootNode = loadRootAndSnapshot("remote_snapshot", remoteSnapshot, null);
		
        Map<String, Object[]> localDiff = loadLocalChanges(localSnapshot, d);        
        Map<String, Object[]> remoteDiff = loadRemoteChanges(remoteSnapshot);

        Map<String, Object[]> changes = mergeChanges(remoteDiff, localDiff);
        //System.out.println(changes);
        Map<String, Object[]> remainingChanges = applyChanges(changes, d);
        if(again != null && again.size() > 0){
        	remainingChanges.putAll(again);
        }
        //System.out.println(remainingChanges);
        if(remainingChanges.size() > 0){
        	List<SyncChange> c = SyncChange.MapToSyncChanges(remainingChanges, "unique-job");
        	for(int i=0;i<c.size();i++){
        		syncDao.create(c.get(i));
        	}
        }
        
        // TODO handle DL / UP failed! 
        takeLocalSnapshot(localRootNode, d, null, true);
        takeRemoteSnapshot(remoteRootNode, null, true);
		
        Manager.getInstance().updateSynchroState(false);
	}
	
	protected Map<String, Object[]> applyChanges(Map<String, Object[]> changes, File localFolder) throws Exception{
		Iterator<Map.Entry<String, Object[]>> it = changes.entrySet().iterator();
		Map<String, Object[]> notApplied = new TreeMap<String, Object[]>();
		RestRequest rest = new RestRequest();
		while(it.hasNext()){
			Map.Entry<String, Object[]> entry = it.next();
			String k = entry.getKey();
			Object[] value = entry.getValue().clone();
			Integer v = (Integer)value[0];
			Node n = (Node)value[1];
			try{
				
				if(v == TASK_LOCAL_GET_CONTENT){
					
					Node node = new Node(Node.NODE_TYPE_ENTRY, "", null);
					node.setPath(k);
					File targetFile = new File(localFolder, k);
					this.logChange("Downloading file from server", k);
					this.synchronousDL(node, targetFile);
					if(!targetFile.exists() || targetFile.length() != Integer.parseInt(n.getPropertyValue("bytesize"))){
						throw new Exception("Error while downloading file from server");
					}
					
				}else if(v == TASK_LOCAL_MKDIR){
					
					File f = new File(localFolder, k);
					if(!f.exists()) {
						this.logChange("Creating local folder", k);
						boolean res = f.mkdirs();
						if(!res){
							throw new Exception("Error while creating local folder");
						}
					}
					
				}else if(v == TASK_LOCAL_REMOVE){
					
					this.logChange("Remove local resource", k);
					File f = new File(localFolder, k);
					if(f.exists()){
						boolean res = f.delete();
						if(!res){
							throw new Exception("Error while removing local resource");
						}
					}
					
				}else if(v == TASK_REMOTE_MKDIR){
					
					this.logChange("Creating remote folder", k);
					Node currentDirectory = new Node(Node.NODE_TYPE_ENTRY, "", null);
					int lastSlash = k.lastIndexOf("/");
					currentDirectory.setPath(k.substring(0, lastSlash));
					RestStateHolder.getInstance().setDirectory(currentDirectory);
					rest.getStatusCodeForRequest(AjxpAPI.getInstance().getMkdirUri(k.substring(lastSlash+1)));
					JSONObject object = rest.getJSonContent(AjxpAPI.getInstance().getStatUri(k));
					if(!object.has("mtime")){
						throw new Exception("Could not create remote folder");
					}
					
				}else if(v == TASK_REMOTE_PUT_CONTENT){
	
					this.logChange("Uploading file to server", k);
					Node currentDirectory = new Node(Node.NODE_TYPE_ENTRY, "", null);
					int lastSlash = k.lastIndexOf("/");
					currentDirectory.setPath(k.substring(0, lastSlash));
					RestStateHolder.getInstance().setDirectory(currentDirectory);
					this.synchronousUP(currentDirectory, new File(localFolder, k));
					JSONObject object = rest.getJSonContent(AjxpAPI.getInstance().getStatUri(k));
					if(!object.has("size") || object.getInt("size") != Integer.parseInt(n.getPropertyValue("bytesize"))){
						throw new Exception("Could not upload file to the server");
					}
					
				}else if(v == TASK_REMOTE_REMOVE){
					
					this.logChange("Delete remote resource", k);
					Node currentDirectory = new Node(Node.NODE_TYPE_ENTRY, "", null);
					int lastSlash = k.lastIndexOf("/");
					currentDirectory.setPath(k.substring(0, lastSlash));
					RestStateHolder.getInstance().setDirectory(currentDirectory);
					rest.getStatusCodeForRequest(AjxpAPI.getInstance().getDeleteUri(k));
					JSONObject object = rest.getJSonContent(AjxpAPI.getInstance().getStatUri(k));
					if(object.has("mtime")){ // Still exists, should be empty!
						throw new Exception("Could not remove the resource from the server");
					}
					
				}else if(v == TASK_DO_NOTHING && value[2] == STATUS_CONFLICT){
					
					this.logChange("Conflict detected on this resource!", k);
					notApplied.put(k, value);
					
				}			
			}catch(Exception e){
				value[2] = STATUS_ERROR;
				notApplied.put(k, value);
			}				
		}
		return notApplied;
	}
	
	protected void logChange(String action, String path){
		Manager.getInstance().notifyUser("AjaXplorer Synchro", action+ " : "+path);
	}
	
	protected Map<String, Object[]> mergeChanges(Map<String, Object[]> remoteDiff, Map<String, Object[]> localDiff){
		Map<String, Object[]> changes = new TreeMap<String, Object[]>();
		Iterator<Map.Entry<String, Object[]>> it = remoteDiff.entrySet().iterator();
		while(it.hasNext()){
			Map.Entry<String, Object[]> entry = it.next();
			String k = entry.getKey();
			Object[] value = entry.getValue();
			Integer v = (Integer)value[0]; 		
			
			if(localDiff.containsKey(k)){
				value[0] = TASK_DO_NOTHING;
				value[2] = STATUS_CONFLICT;
				changes.put(k, value);
				localDiff.remove(k);
			}
			if(v == NODE_CHANGE_STATUS_FILE_CREATED || v == NODE_CHANGE_STATUS_MODIFIED){
				value[0] = TASK_LOCAL_GET_CONTENT;
				changes.put(k, value);
			}else if(v == NODE_CHANGE_STATUS_FILE_DELETED || v == NODE_CHANGE_STATUS_DIR_DELETED){
				value[0] = TASK_LOCAL_REMOVE;
				changes.put(k, value);
			}else if(v == NODE_CHANGE_STATUS_DIR_CREATED){
				value[0] = TASK_LOCAL_MKDIR;
				changes.put(k, value);
			}
		}
		it = localDiff.entrySet().iterator();
		while(it.hasNext()){
			Map.Entry<String, Object[]> entry = it.next();
			String k = entry.getKey();
			Object[] value = entry.getValue();
			Integer v = (Integer)value[0]; 
			if(v == NODE_CHANGE_STATUS_FILE_CREATED || v == NODE_CHANGE_STATUS_MODIFIED){
				value[0] = TASK_REMOTE_PUT_CONTENT;
				changes.put(k, value);
			}else if(v == NODE_CHANGE_STATUS_FILE_DELETED || v == NODE_CHANGE_STATUS_DIR_DELETED){
				value[0] = TASK_REMOTE_REMOVE;
				changes.put(k, value);
			}else if(v == NODE_CHANGE_STATUS_DIR_CREATED){
				value[0] = TASK_REMOTE_MKDIR;
				changes.put(k, value);
			}			
		}
		return changes;
	}

	protected Node loadRootAndSnapshot(String type, final List<Node> snapshot, File localFolder) throws SQLException{
		
		List<Node> l = nodeDao.queryForEq("resourceType", type);
		final Node root;
		if(l.size() > 0){
			root = l.get(0);
			CloseableIterator<Node> it = root.children.iteratorThrow();
			while(it.hasNext()){
				snapshot.add(it.next());
			}
		}else{
			root = new Node(type, "", null);
			root.properties = nodeDao.getEmptyForeignCollection("properties");
			if(localFolder != null) root.setPath(localFolder.getAbsolutePath());
			nodeDao.create(root);
		}

		return root;
	}
	
	
	
	protected void takeLocalSnapshot(final Node rootNode, final File localFolder, final List<Node> accumulator, boolean save) throws Exception{
			
		if(save){
			nodeDao.delete(rootNode.children);
		}
		//final List<Node> list = new ArrayList<Node>();
		nodeDao.callBatchTasks(new Callable<Void>() {
			public Void call() throws Exception{
				listDirRecursive(localFolder, rootNode, accumulator);
				return null;
			}
		});
		if(save){
			nodeDao.update(rootNode);
		}
	}
	
	protected Map<String, Object[]> loadLocalChanges(List<Node> snapshot, final File localFolder) throws Exception{
	
		final Node root = new Node("local_tmp", "", null);
		root.setPath(localFolder.getAbsolutePath());
		final List<Node> list = new ArrayList<Node>();
		takeLocalSnapshot(root, localFolder, list, false);
		
		/*
		final List<Node> list = new ArrayList<Node>();
		nodeDao.callBatchTasks(new Callable<Void>() {
			public Void call() throws Exception{
				listDirRecursive(localFolder, nodeDao, root, list);
				return null;
			}
		});
		//nodeDao.update(root);  // DO NOT SAVE THIS ONE
		*/
		
		Map<String, Object[]> diff = this.diffNodeLists(list, snapshot);
		//System.out.println(diff);
		return diff;
		
	}
	
	protected void listDirRecursive(File directory, Node root, List<Node> accumulator) throws SQLException{
		
		File[] children = directory.listFiles();
		for(int i=0;i<children.length;i++){
			Node newNode = new Node(Node.NODE_TYPE_ENTRY, children[i].getName(), root);
			nodeDao.create(newNode);
			String p =children[i].getAbsolutePath().substring(root.getPath(true).length()).replace("\\", "/");
			//System.out.println(p);
			newNode.setPath(p);
			//System.out.println(newNode);
			newNode.properties = nodeDao.getEmptyForeignCollection("properties");			
			newNode.setLastModified(new Date(children[i].lastModified()));
			if(children[i].isDirectory()){
				listDirRecursive(children[i], root, accumulator);
			}else{				
				newNode.addProperty("bytesize", String.valueOf(children[i].length()));
				newNode.setLeaf();
			}
			nodeDao.update(newNode);
			if(accumulator != null){
				accumulator.add(newNode);
			}
		}
		
	}
	
	protected void takeRemoteSnapshot(final Node rootNode, final List<Node> accumulator, boolean save) throws Exception{
		
		if(save){
			nodeDao.delete(rootNode.children);
		}
		RestRequest r = new RestRequest();
		URI uri = AjxpAPI.getInstance().getRecursiveLsDirectoryUri(rootNode);
		Document d = r.getDocumentContent(uri);
		
		final NodeList entries = d.getDocumentElement().getChildNodes();
		if(entries != null && entries.getLength() > 0){
			nodeDao.callBatchTasks(new Callable<Void>() {
				public Void call() throws Exception{
					parseNodesRecursive(entries, rootNode, accumulator);
					return null;
				}
			});			
		}	
		if(save){
			nodeDao.update(rootNode);
		}
	}
	
	protected Map<String, Object[]> loadRemoteChanges(List<Node> snapshot) throws URISyntaxException, Exception{				
		
		final Node root = new Node("remote_root", "", currentRepository);
		root.setPath("/");
		final ArrayList<Node> list = new ArrayList<Node>();
		takeRemoteSnapshot(root, list, false);
		
		Map<String, Object[]> diff = this.diffNodeLists(list, snapshot);
		//System.out.println(diff);
		return diff;
	}
	
	protected void parseNodesRecursive(NodeList entries, Node parentNode, List<Node> list) throws SQLException{
		for(int i=0; i< entries.getLength(); i++){
			org.w3c.dom.Node xmlNode = entries.item(i);
			Node entry = new Node(Node.NODE_TYPE_ENTRY, "", parentNode);
			nodeDao.create(entry);
			entry.properties = nodeDao.getEmptyForeignCollection("properties");
			entry.initFromXmlNode(xmlNode);
			nodeDao.update(entry);
			if(list != null){
				list.add(entry);
			}
			if(xmlNode.getChildNodes().getLength() > 0){
				parseNodesRecursive(xmlNode.getChildNodes(), parentNode, list);				
			}
		}
		
	}
	
	protected Map<String, Object[]> diffNodeLists(List<Node> current, List<Node> snapshot){
		List<Node> saved = new ArrayList<Node>(snapshot);
		TreeMap<String, Object[]> diff = new TreeMap<String, Object[]>();
		Iterator<Node> cIt = current.iterator();
		while(cIt.hasNext()){
			Node c = cIt.next();
			Iterator<Node> sIt = saved.iterator();
			boolean found = false;
			while(sIt.hasNext() && !found){
				Node s = sIt.next();
				if(s.getPath(true).equals(c.getPath(true))){
					found = true;
					if(c.isLeaf()){// FILE : compare date & size
						if(c.getLastModified().after(s.getLastModified()) || !c.getPropertyValue("bytesize").equals(s.getPropertyValue("bytesize")) ){
							diff.put(c.getPath(true), makeTodoObject(NODE_CHANGE_STATUS_MODIFIED, c));
						}
					}
					saved.remove(s);
				}
			}
			if(!found){				
				diff.put(c.getPath(true), makeTodoObject((c.isLeaf()?NODE_CHANGE_STATUS_FILE_CREATED:NODE_CHANGE_STATUS_DIR_CREATED), c));
			}
		}
		if(saved.size()>0){
			Iterator<Node> sIt = saved.iterator();
			while(sIt.hasNext()){
				Node s = sIt.next();
				diff.put(s.getPath(true), makeTodoObject((s.isLeaf()?NODE_CHANGE_STATUS_FILE_DELETED:NODE_CHANGE_STATUS_DIR_DELETED), s));
			}
		}
		return diff;
	}
	
	protected Object[] makeTodoObject(Integer nodeStatus, Node node){
		Object[] val = new Object[3];
		val[0] = nodeStatus;
		val[1] = node;
		val[2] = STATUS_TODO;
		return val;
	}
	
	protected void synchronousUP(Node folderNode, File sourceFile) throws Exception{

		long totalSize = sourceFile.length();
		if(!sourceFile.exists() || totalSize == 0){
			throw new FileNotFoundException("Cannot find file !");
		}
		
    	RestRequest rest = new RestRequest();
    	// Ping to make sure the user is logged
    	rest.getStatusCodeForRequest(AjxpAPI.getInstance().getAPIUri());
    	//final long filesize = totalSize; 
    	String targetName = sourceFile.getName();
    	rest.getStringContent(AjxpAPI.getInstance().getUploadUri(folderNode.getPath(true)), null, sourceFile, targetName);
			          		
	}
	
	protected void synchronousDL(Node node, File targetFile) throws Exception{
    	RestRequest rest = new RestRequest();
    	//rest.getStatusCodeForRequest(DataProvider.dataHolder().urlHandler.getAPIUri());
    	
        int postedProgress = 0;
        int buffersize = 16384;
        int count = 0;
        
		URI uri = AjxpAPI.getInstance().getDownloadUri(node.getPath(true));
		HttpEntity entity = rest.getNotConsumedResponseEntity(uri, null);
		long fullLength = entity.getContentLength();
		
		InputStream input = entity.getContent();
		BufferedInputStream in = new BufferedInputStream(input,buffersize);
        
		FileOutputStream output = new FileOutputStream(targetFile.getPath());
		BufferedOutputStream out = new BufferedOutputStream(output);

        byte data[] = new byte[buffersize];
        int total = 0;
                    
        long startTime = System.nanoTime();
        long lastTime = startTime;
        int lastTimeTotal = 0;
        
        long secondLength = 1000000000;
        long interval = (long) 2*secondLength;	        
        
        while ( (count = in.read(data)) != -1 ) {
        	long duration = System.nanoTime()-lastTime ;
        	
        	int tmpTotal = total+count;
            // publishing the progress....
            int tmpProgress = (int)(tmpTotal*100/fullLength);
            if (tmpProgress-postedProgress > 0 || duration > secondLength) {
            	if (duration > interval) {
                	lastTime = System.nanoTime();
                	long lastTimeBytes = (long)((tmpTotal-lastTimeTotal)*secondLength/1024/1000);
                	long speed = (lastTimeBytes/(duration));
                	double bytesleft =(double)(((double)fullLength-(double)tmpTotal)/1024); 
                	double ETC = bytesleft/(speed*10);
            	}
            	postedProgress=tmpProgress;
            }
            out.write(data, 0, count);
            total = tmpTotal;
        }	        
        out.flush();
    	if(out != null) out.close();
        if(in != null) in.close();
		
	}
	
	protected void logMemory(){
		System.out.println("Total memory (bytes): " + 
		        Math.round(Runtime.getRuntime().totalMemory() / (1024 * 1024)) + "M");
	}
	
	protected void logDocument(Document d){
		try{
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			//initialize StreamResult with File object to save to file
			StreamResult result = new StreamResult(new StringWriter());
			DOMSource source = new DOMSource(d);
			transformer.transform(source, result);	
			String xmlString = result.getWriter().toString();
			System.out.println(xmlString);
		}catch(Exception e){
		}		
	}

}
