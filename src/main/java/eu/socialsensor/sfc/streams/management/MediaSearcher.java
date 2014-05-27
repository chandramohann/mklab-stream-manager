package eu.socialsensor.sfc.streams.management;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.xml.sax.SAXException;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import eu.socialsensor.framework.client.search.solr.SolrDyscoHandler;
import eu.socialsensor.framework.common.domain.Feed;
import eu.socialsensor.framework.common.domain.Item;
import eu.socialsensor.framework.common.domain.Keyword;
import eu.socialsensor.framework.common.domain.Query;
import eu.socialsensor.framework.common.domain.dysco.Dysco;
import eu.socialsensor.framework.common.domain.dysco.Dysco.DyscoType;
import eu.socialsensor.framework.common.domain.dysco.Entity;
import eu.socialsensor.framework.common.domain.dysco.Message;
import eu.socialsensor.framework.common.domain.dysco.Message.Action;
import eu.socialsensor.framework.common.domain.feeds.KeywordsFeed;
import eu.socialsensor.framework.streams.Stream;
import eu.socialsensor.framework.streams.StreamConfiguration;
import eu.socialsensor.framework.streams.StreamException;
import eu.socialsensor.sfc.builder.FeedsCreator;
import eu.socialsensor.sfc.builder.SolrQueryBuilder;
import eu.socialsensor.sfc.builder.input.DataInputType;
import eu.socialsensor.sfc.streams.StreamsManagerConfiguration;
import eu.socialsensor.sfc.streams.monitors.StreamsMonitor;

public class MediaSearcher {
	private static String REDIS_HOST = "redis.host";
	private static String SOLR_HOST = "solr.hostname";
	private static String SOLR_SERVICE = "solr.service";
	private static String DYSCO_COLLECTION = "dyscos.collection";
	private static String CHANNEL = "channel";
	
	public final Logger logger = Logger.getLogger(StreamsManager.class);
	
	enum MediaSearcherState {
		OPEN, CLOSE
	}
	private MediaSearcherState state = MediaSearcherState.CLOSE;
	
	private StreamsManagerConfiguration config = null;
	
	private StoreManager storeManager;
	
	private StreamsMonitor monitor;

	private Jedis subscriberJedis;
	
	private DyscoRequestHandler dyscoRequestHandler;
	private DyscoRequestReceiver dyscoRequestReceiver;
	private DyscoUpdateAgent dyscoUpdateAgent;
	private TrendingSearchHandler trendingSearchHandler;
	private CustomSearchHandler customSearchHandler;
	private SolrQueryBuilder queryBuilder;
	
	private String redisHost;
	private String solrHost;
	private String solrService;
	private String dyscoCollection;
	
	private boolean keyHold = false;
	
	private Map<String, Stream> streams = null;
	
	private Queue<Dysco> requests = new LinkedList<Dysco>();
	private Queue<String> dyscosToUpdate = new LinkedList<String>();
	
	private Map<String,List<Query>> dyscosToQueries = new HashMap<String,List<Query>>();
	
	public MediaSearcher(StreamsManagerConfiguration config) throws StreamException{
		if (config == null) {
			throw new StreamException("Manager's configuration must be specified");
		}

		this.config = config;
		this.redisHost = config.getParameter(MediaSearcher.REDIS_HOST);
		this.solrHost = config.getParameter(MediaSearcher.SOLR_HOST);
		this.solrService = config.getParameter(MediaSearcher.SOLR_SERVICE);
		this.dyscoCollection = config.getParameter(MediaSearcher.DYSCO_COLLECTION);
		
		
		//Set up the Streams
		initStreams();
		
		//Set up the Storages
		storeManager = new StoreManager(config);
		
	}
	
	/**
	 * Opens Manager by starting the auxiliary modules and setting up
	 * the database for reading/storing
	 * @throws StreamException
	 */
	public synchronized void open() throws StreamException {
		if (state == MediaSearcherState.OPEN) {
			return;
		}
		state = MediaSearcherState.OPEN;
		
		storeManager.start();	
		logger.info("Store Manager is ready to store.");
		
		for (String streamId : streams.keySet()) {
			logger.info("MediaSearcher - Start Stream : "+streamId);
			StreamConfiguration sconfig = config.getStreamConfig(streamId);
			Stream stream = streams.get(streamId);
			stream.setHandler(storeManager);
			stream.open(sconfig);
		}
		
		logger.info("Streams are now open");
		
		//If there are Streams to monitor start the StreamsMonitor
		if(streams != null && !streams.isEmpty()){
			monitor = new StreamsMonitor(streams.size());
			monitor.addStreams(streams);
			logger.info("Streams added to monitor");
		}
		else {
			logger.error("Streams Monitor cannot be started");
		}
		
		//start handlers
		this.dyscoRequestHandler = new DyscoRequestHandler();
		this.dyscoRequestReceiver = new DyscoRequestReceiver();
		this.dyscoUpdateAgent = new DyscoUpdateAgent();
		this.trendingSearchHandler = new TrendingSearchHandler(this);
		this.customSearchHandler = new CustomSearchHandler(this);
		
		try {
			this.queryBuilder = new SolrQueryBuilder();
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		dyscoRequestHandler.start();
		dyscoUpdateAgent.start();
        trendingSearchHandler.start();
		customSearchHandler.start();
		
		JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool = new JedisPool(poolConfig, redisHost, 6379, 0);
        this.subscriberJedis = jedisPool.getResource();
     
		new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                	System.out.println("Try to subscribe to redis");
                
                    subscriberJedis.subscribe(dyscoRequestReceiver,config.getParameter(MediaSearcher.CHANNEL));
                   
                } catch (Exception e) {
                }
            }
        }).start();
		
		state = MediaSearcherState.OPEN;
	
    	
		Runtime.getRuntime().addShutdownHook(new Shutdown(this));
		
//		SolrDyscoHandler solrDyscoHandler = SolrDyscoHandler.getInstance(solrHost+"/"+solrService+"/"+dyscoCollection);
//		
//		Dysco testDysco = solrDyscoHandler.findDyscoLight("0eb98fee-cb0a-43b0-b542-2b8fc1401855");
//		requests.add(testDysco);
	}
	
	/**
	 * Closes Manager along with its auxiliary modules
	 * @throws StreamException
	 */
	public synchronized void close() throws StreamException {
		
		if (state == MediaSearcherState.CLOSE) {
			return;
		}
		
		try{
			for (Stream stream : streams.values()) {
				stream.close();
			}
			
//			if(dyscoRequestReceiver != null){
//				dyscoRequestReceiver.close();
//				System.out.println("dyscoRequestReceiver closed");
//			}
//			
//			if(dyscoRequestHandler != null){
//				dyscoRequestHandler.close();
//				System.out.println("dyscoRequestHandler closed");
//			}
			state = MediaSearcherState.CLOSE;
			System.out.println("MediaSearcher closed");
		}catch(Exception e) {
			throw new StreamException("Error during streams close",e);
		}
	}
	
	/**
	 * Searches for a dysco request depending on its feeds
	 * @param feeds to search
	 */
	public synchronized List<Item> search(List<Feed> feeds,Set<String>streamsToSearch){
		Integer totalItems = 0; 
		
		long t1 = System.currentTimeMillis();
		
		if(feeds != null && !feeds.isEmpty()){
			
			monitor.retrieveFromSelectedStreams(streamsToSearch, feeds);
			while(!monitor.areAllStreamsFinished()){
				
			}
			totalItems = monitor.getTotalRetrievedItems().size();
		}
			
		long t2 = System.currentTimeMillis();
		
		logger.info("Total items fetched : "+totalItems+" in "+(t2-t1)/1000+" seconds");
		return monitor.getTotalRetrievedItems();
	}
	
	
	/**
	 * Initializes the streams that correspond to the wrappers 
	 * that are used for multimedia retrieval
	 * @throws StreamException
	 */
	private void initStreams() throws StreamException {
		streams = new HashMap<String,Stream>();
		try{
			for (String streamId : config.getStreamIds()){
				StreamConfiguration sconfig = config.getStreamConfig(streamId);
				streams.put(streamId,(Stream)Class.forName(sconfig.getParameter(StreamConfiguration.CLASS_PATH)).newInstance());
			}
		}catch(Exception e) {
			e.printStackTrace();
			throw new StreamException("Error during streams initialization",e);
		}
	}
	
	/**
	 * Class for searching for custom dysco requests 
	 * @author ailiakop
	 *
	 */
	public class CustomSearchHandler extends Thread {
		private Queue<String> customDyscoQueue = new LinkedList<String>();
		
		private Map<String,List<Feed>> inputFeedsPerDysco = new HashMap<String,List<Feed>>();
		private Map<String,Long> requestsLifetime = new HashMap<String,Long>();
		private Map<String,Long> requestsTimestamps = new HashMap<String,Long>();
		
		private MediaSearcher searcher;

		private boolean isAlive = true;
		
		private static final long frequency = 2 * 300000; //ten minutes
		private static final long periodOfTime = 48 * 3600000; //two days
		
		public CustomSearchHandler(MediaSearcher mediaSearcher){
			this.searcher = mediaSearcher;
			
		}
		
		public void addCustomDysco(String dyscoId,List<Feed> inputFeeds){
			logger.info("New incoming dysco : "+dyscoId+" with "+inputFeeds.size()+" searchable feeds");
			customDyscoQueue.add(dyscoId);
			inputFeedsPerDysco.put(dyscoId, inputFeeds);
			requestsLifetime.put(dyscoId, System.currentTimeMillis());
			requestsTimestamps.put(dyscoId, System.currentTimeMillis());
		}
		
		public void deleteCustomDysco(String dyscoId){
			inputFeedsPerDysco.remove(dyscoId);
			requestsLifetime.remove(dyscoId);
			requestsTimestamps.remove(dyscoId);
		}
		
		public void run(){
			String dyscoId = null;
			while(isAlive){
				updateCustomQueue();
				dyscoId = poll();
				if(dyscoId == null){
					continue;
				}
				else{
					logger.info("Media Searcher handling #"+dyscoId);
					List<Feed> feeds = inputFeedsPerDysco.get(dyscoId);
					inputFeedsPerDysco.remove(dyscoId);
					searcher.search(feeds,streams.keySet());
					
				}
				
			}
		}
		/**
		 * Polls a trending dysco request from the queue
		 * @return
		 */
		private String poll(){
			synchronized (customDyscoQueue) {					
				if (!customDyscoQueue.isEmpty()) {
					String request = customDyscoQueue.poll();
					return request;
				}
				try {
					customDyscoQueue.wait(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				return null;
			}
		}
		/**
		 * Stops TrendingSearchHandler
		 */
		public synchronized void close(){
			isAlive = false;
		}
	
		/**
		 * Updates the queue of custom dyscos' requests and re-examines or deletes 
		 * requests according to their time in the system
		 */
		private synchronized void updateCustomQueue(){
			
			List<String> requestsToRemove = new ArrayList<String>();
			long currentTime = System.currentTimeMillis();
			
			for(Map.Entry<String, Long> entry : requestsLifetime.entrySet()){
			//	System.out.println("Checking dysco : "+entry.getKey().getId()+" that has time in system : "+(currentTime - entry.getValue())/1000);
				
				if(currentTime - entry.getValue() > frequency){
					
					entry.setValue(currentTime);
					String requestToSearch = entry.getKey();
					customDyscoQueue.add(requestToSearch);
					requestsLifetime.put(entry.getKey(), System.currentTimeMillis());
					if(currentTime - requestsTimestamps.get(entry.getKey())> periodOfTime){
						
						requestsToRemove.add(entry.getKey());
					}
						
				}
				
			}
			
			if(!requestsToRemove.isEmpty()){
				for(String requestToRemove : requestsToRemove){
					deleteCustomDysco(requestToRemove);
				}
				requestsToRemove.clear();	
			}
			
		}
		
		
	}
	
	/**
	 * Class for searching for trending dysco requests 
	 * @author ailiakop
	 *
	 */
	public class TrendingSearchHandler extends Thread {
		
		private BlockingQueue<String> trendingDyscoQueue = new LinkedBlockingDeque<String>(100);
		
		private Map<String,List<Feed>> inputFeedsPerDysco = new HashMap<String,List<Feed>>();
		
		private List<Item> retrievedItems = new ArrayList<Item>();
		
		private Set<String> primaryStreamsToSearch = new HashSet<String>();
		
		private MediaSearcher searcher;

		private boolean isAlive = true;
		
		
		private Date retrievalDate; 

		public TrendingSearchHandler(MediaSearcher mediaSearcher){
			this.searcher = mediaSearcher;
			primaryStreamsToSearch.addAll(streams.keySet());
			//primaryStreamsToSearch.remove("Facebook");
		}
		
		public void addTrendingDysco(String dyscoId,List<Feed> inputFeeds){
			logger.info("New incoming dysco : "+dyscoId+" with "+inputFeeds.size()+" searchable feeds");
			trendingDyscoQueue.add(dyscoId);
			inputFeedsPerDysco.put(dyscoId, inputFeeds);
		}
		
		public void run(){
			String dyscoId = null;
			while(isAlive){
				
				dyscoId = poll();
				if(dyscoId == null){
					continue;
				}
				else{
					keyHold = true;
					searchForDysco(dyscoId);
					keyHold = false;
				}
					
			}
		}
		/**
		 * Polls a trending dysco request from the queue
		 * @return
		 */
		private String poll(){
			synchronized (trendingDyscoQueue) {					
				if (!trendingDyscoQueue.isEmpty()) {
					String request = trendingDyscoQueue.poll();
					
					return request;
				}
				try {
					trendingDyscoQueue.wait(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				return null;
			}
		}
		
		private synchronized void searchForDysco(String dyscoId){
			long start = System.currentTimeMillis();
			
			logger.info("Media Searcher handling #"+dyscoId);
			
			//first search
			List<Feed> feeds = inputFeedsPerDysco.get(dyscoId);
			retrievalDate = new Date(System.currentTimeMillis());
			inputFeedsPerDysco.remove(dyscoId);
			retrievedItems = searcher.search(feeds,primaryStreamsToSearch);
			
			long t1 = System.currentTimeMillis();
			
			System.out.println("Time for First Search for dysco: "+dyscoId+" is "+(t1-start)/1000+" sec ");
			
			long t2 = System.currentTimeMillis();
			
			//second search
			List<Query> queries = queryBuilder.getFurtherProcessedSolrQueries(retrievedItems,5,dyscoId);
			
			System.out.println("Number of additional queries : "+queries.size());
			
			long t3 = System.currentTimeMillis();
			
			System.out.println("Time for computing queries for dysco:+ "+dyscoId+" is "+(t3-t2)/1000+" sec ");
			
			dyscosToQueries.put(dyscoId, queries);
			dyscosToUpdate.add(dyscoId);
			
			List<Feed> newFeeds = translateQueriesToKeywordsFeeds(queries,retrievalDate);
			
			searcher.search(newFeeds,streams.keySet());
			
			long t4 = System.currentTimeMillis();
			
			System.out.println("Time for Second Search for dysco:+ "+dyscoId+" is "+(t4-t3)/1000+" sec ");
			
			long end = System.currentTimeMillis();
			
			System.out.println(new Date(System.currentTimeMillis())+" - Total Time searching for dysco:+ "+dyscoId+" is "+(end-start)/1000+" sec ");
		}
		
		/**
		 * Stops TrendingSearchHandler
		 */
		public synchronized void close(){
			isAlive = false;
		}
		
		private List<Feed> translateQueriesToKeywordsFeeds(List<Query> queries,Date dateToRetrieve)
		{	
			List<Feed> feeds = new ArrayList<Feed>();
			
			for(Query query : queries){
				UUID UUid = UUID.randomUUID(); 
				feeds.add(new KeywordsFeed(new Keyword(query.getName(),query.getScore()),dateToRetrieve,UUid.toString()));
			}
			
			return feeds;
		}
	}
	
	/**
	 * Class for handling incoming dysco requests that are received with redis
	 * @author ailiakop
	 *
	 */
	private class DyscoRequestHandler extends Thread {

		private boolean isAlive = true;
		
		private FeedsCreator feedsCreator;
		
		private List<Feed> feeds;
		
		public DyscoRequestHandler(){
			
		}
		
		public void run(){
			Dysco receivedDysco = null;
			while(isAlive){
				receivedDysco = poll();
				if(receivedDysco == null){
					continue;
				}
				else{
					
					feedsCreator = new FeedsCreator(DataInputType.DYSCO,receivedDysco);
					feeds = feedsCreator.getQuery();
					
					if(receivedDysco.getDyscoType().equals(DyscoType.TRENDING)){
						trendingSearchHandler.addTrendingDysco(receivedDysco.getId(), feeds);
					}
					else if(receivedDysco.getDyscoType().equals(DyscoType.CUSTOM)){
						customSearchHandler.addCustomDysco(receivedDysco.getId(), feeds);
					}
					else{
						logger.error("Unsupported dysco - Cannot be processed from MediaSearcher");
					}
				}
			}
		}
		
		/**
		 * Polls a trending dysco request from the queue
		 * @return
		 */
		private Dysco poll(){
			synchronized (requests) {					
				if (!requests.isEmpty() && !keyHold) {
					Dysco request = requests.poll();
					
					try {
						requests.wait(1000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return request;
				}
				try {
					System.out.println("Trending Dyscos remain to be served: "+requests.size());
					requests.wait(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				return null;
			}
		}
		
		public void close(){
			isAlive = false;
		}
	}
	
	public class DyscoUpdateAgent extends Thread{
		private SolrDyscoHandler solrdyscoHandler;
		private boolean isAlive = true;
		
		public DyscoUpdateAgent(){
			
			this.solrdyscoHandler = SolrDyscoHandler.getInstance(solrHost+"/"+solrService+"/"+dyscoCollection);
		}
		
		public void run(){
			String dyscoToUpdate = null;
			while(isAlive){
				dyscoToUpdate = poll();
				if(dyscoToUpdate == null){
					continue;
				}
				else{
					List<Query> solrQueries = dyscosToQueries.get(dyscoToUpdate);
					Dysco updatedDysco = solrdyscoHandler.findDyscoLight(dyscoToUpdate);
					
					if(solrQueries == null || solrQueries.isEmpty()){
						for(String hash : updatedDysco.getHashtags().keySet()){
							Query query = new Query(hash,updatedDysco.getHashtags().get(hash));
							
							solrQueries.add(query);
						}
						
						for(Entity ent : updatedDysco.getEntities()){
							Query query = new Query(ent.getName(),ent.getCont());
							
							solrQueries.add(query);
						}
					}
					
					updatedDysco.setSolrQueries(solrQueries);
					solrdyscoHandler.insertDysco(updatedDysco);
					dyscosToQueries.remove(dyscoToUpdate);
					
					System.out.println("Dysco : "+updatedDysco.getId()+" is updated");
				}
			}
		}
		
		/**
		 * Polls a trending dysco request from the queue
		 * @return
		 */
		private String poll(){
			synchronized (dyscosToUpdate) {					
				if (!dyscosToUpdate.isEmpty()) {
					String dyscoToUpdate = dyscosToUpdate.poll();
					return dyscoToUpdate;
				}
				try {
					dyscosToUpdate.wait(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				return null;
			}
		}
		
		public void close(){
			isAlive = false;
		}
	}
	
	public class DyscoRequestReceiver extends JedisPubSub{

		private SolrDyscoHandler solrdyscoHandler;
		
		public DyscoRequestReceiver(){
			
			this.solrdyscoHandler = SolrDyscoHandler.getInstance(solrHost+"/"+solrService+"/"+dyscoCollection);
		}
		/**
		 * Alerts the system that a new dysco request is received
		 * New dysco requests are added to a queue to be further
		 * processed by the DyscoRequestFeedsCreator thread.
		 * In case the dysco request already exists in mongo db,
		 * it is deleted from the system and not processed further.
		 */
	    @Override
	    public void onMessage(String channel, String message) {
	    	
	    	logger.info("Received dysco request : "+message);
	    	Message dyscoMessage = Message.create(message);
	    	
	    	String dyscoId = dyscoMessage.getDyscoId();
	    	Action action = dyscoMessage.getAction();
	    	
	    	switch(action){
		    	case NEW : 
		    		System.out.println("New dysco with id : "+dyscoId+" created");
		    		Dysco dysco = solrdyscoHandler.findDyscoLight(dyscoId);
		    		
		    		if(dysco == null){
		    			logger.error("Invalid dysco request");
		    			return;
		    		}
		    		
		    		requests.add(dysco);
		    		break;
		    	case UPDATE:
		    		logger.info("Dysco with id : "+dyscoId+" updated");
		    		break;
		    	case DELETE:
		    		logger.info("Dysco with id : "+dyscoId+" deleted");
		    		break;
	    	}
	    	
	    }
	 
	    @Override
	    public void onPMessage(String pattern, String channel, String message) {
	    	// Do Nothing
	    }
	 
	    @Override
	    public void onSubscribe(String channel, int subscribedChannels) {
	    	// Do Nothing
	    }
	 
	    @Override
	    public void onUnsubscribe(String channel, int subscribedChannels) {
	    	// Do Nothing
	    }
	 
	    @Override
	    public void onPUnsubscribe(String pattern, int subscribedChannels) {
	    	// Do Nothing
	    }
	 
	    @Override
	    public void onPSubscribe(String pattern, int subscribedChannels) {
	    	// Do Nothing
	    }
	    
	    public void close(){
	    	logger.info("Closing redis..");
	    	subscriberJedis.quit();
	    }
	}
	
	/**
	 * Class in case system is shutdown 
	 * Responsible to close all services 
	 * that are running at the time being
	 * @author ailiakop
	 *
	 */
	
	private class Shutdown extends Thread {
		private MediaSearcher searcher = null;

		public Shutdown(MediaSearcher searcher) {
			this.searcher = searcher;
		}

		public void run() {
			System.out.println("Shutting down media searcher ...");
			if (searcher != null) {
				try {
					searcher.close();
				} catch (StreamException e) {
					e.printStackTrace();
				}
			}
			System.out.println("Done...");
		}
	}
	

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		File configFile;
		
		if(args.length != 1 ) {
			configFile = new File("./conf/mediasearcher.conf.xml");
		}
		else {
			configFile = new File(args[0]);
		}
		
		try {
			StreamsManagerConfiguration config = StreamsManagerConfiguration.readFromFile(configFile);
			MediaSearcher mediaSearcher = new MediaSearcher(config);
			mediaSearcher.open();
			
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (StreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
