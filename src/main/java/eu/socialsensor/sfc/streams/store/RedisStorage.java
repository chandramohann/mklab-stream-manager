package eu.socialsensor.sfc.streams.store;

import java.io.IOException;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import eu.socialsensor.framework.common.domain.Item;
import eu.socialsensor.framework.common.domain.MediaItem;
import eu.socialsensor.framework.common.domain.WebPage;
import eu.socialsensor.sfc.streams.StorageConfiguration;

/**
 * Class for storing items to redis store
 * @author manosetro
 * @email  manosetro@iti.gr
 *
 */
public class RedisStorage implements StreamUpdateStorage {

	private static String HOST = "redis.host";
	private static String WEBPAGES_CHANNEL = "redis.webpages.channel";
	private static String MEDIA_CHANNEL = "redis.media.channel";
	private static String ITEMS_CHANNEL = "redis.items.channel";
	
	private Jedis publisherJedis;
	private String host;
	
	private String itemsChannel = null;
	private String webPagesChannel = null;
	private String mediaItemsChannel = null;
	
	private String storageName = "Redis";
	
	public RedisStorage(StorageConfiguration config) {
		this.host = config.getParameter(RedisStorage.HOST);
		this.itemsChannel = config.getParameter(RedisStorage.ITEMS_CHANNEL);
		this.webPagesChannel = config.getParameter(RedisStorage.WEBPAGES_CHANNEL);
		this.mediaItemsChannel = config.getParameter(RedisStorage.MEDIA_CHANNEL);
	}
	
	@Override
	public boolean open() {
		try {
			JedisPoolConfig poolConfig = new JedisPoolConfig();
			JedisPool jedisPool = new JedisPool(poolConfig, host, 6379, 0);
		
			this.publisherJedis = jedisPool.getResource();
			return true;
		}
		catch(Exception e) {
			return false;
		}
	}

	@Override
	public void store(Item item) throws IOException {
		if(item == null)
			return;
		
		if(item.isOriginal()) { 	
			if(itemsChannel != null) {
				publisherJedis.publish(itemsChannel, item.toJSONString());
			}
		
			if(mediaItemsChannel != null) {
				for(MediaItem mediaItem : item.getMediaItems()) {
					publisherJedis.publish(mediaItemsChannel, mediaItem.toJSONString());
				}
			}
		
			if(webPagesChannel != null) {
				for(WebPage webPage : item.getWebPages()) {
					publisherJedis.publish(webPagesChannel, webPage.toJSONString());
				}
			}	
		}
	}
	
	@Override
	public void update(Item update) throws IOException {
		
	}
	
	@Override
	public boolean delete(String id) throws IOException {
		return false;
	}
	
	

	@Override
	public void updateTimeslot() {
		
	}

	@Override
	public void close() {
		publisherJedis.disconnect();
	}
	
	@Override
	public boolean checkStatus(StreamUpdateStorage storage) {
		try {
			publisherJedis.info();
			boolean connected = publisherJedis.isConnected();
			if(!connected) {
				connected = reconnect();
			}
			return connected;
		}
		catch(Exception e) {
			return reconnect();
		}
	}
	
	private boolean reconnect() {
		try {
			if(publisherJedis != null) {
				publisherJedis.disconnect();
			}
		}
		catch(Exception e) { }
		try {
			JedisPoolConfig poolConfig = new JedisPoolConfig();
        	JedisPool jedisPool = new JedisPool(poolConfig, host, 6379, 0);
		
        	this.publisherJedis = jedisPool.getResource();
        	publisherJedis.info();
        	return publisherJedis.isConnected();
		}
		catch(Exception e) {
			return false;
		}

	}
	
	@Override
	public boolean deleteItemsOlderThan(long dateThreshold) throws IOException{
	
		return true;
	}
	
	@Override
	public String getStorageName(){
		return this.storageName;
	}

}
