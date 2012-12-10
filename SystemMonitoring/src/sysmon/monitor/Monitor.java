package sysmon.monitor;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;

import sysmon.common.InitiativeCommandHandler;
import sysmon.monitor.crawler.Crawler;
import sysmon.util.GlobalParameters;
import sysmon.util.IPUtil;
import sysmon.util.Out;

import com.google.gson.JsonObject;

/**
 * The monitor that fetches variant kinds of metadata from the machine.
 * @author Yexi Jiang (http://users.cs.fiu.edu/~yjian004)
 *
 */
public class Monitor {
	
	private String managerBrokerAddress;
	private String monitorIPAddress;
	private long moniterInterval = 1;	//	In seconds
	private long metaDataSendingInterval = 1;	//	In seconds
	private Map<String, CrawlerWorker> crawlers;
	private JsonObject assembledStaticMetaData;
	private JsonObject assembledDynamicMetaData;
	private MonitorCommandSender commandSender;
	
	public Monitor(String managerBrokerAddress, long monitoringInterval, long metaDataSendingInterval) {
		this.managerBrokerAddress = managerBrokerAddress;
		this.monitorIPAddress = IPUtil.getFirstAvailableIP();
		this.crawlers = new HashMap<String, CrawlerWorker>();
		this.assembledStaticMetaData = new JsonObject();
		this.assembledDynamicMetaData = new JsonObject();
		setMonitorInterval(monitoringInterval);
		setMetaDataSendingInterval(metaDataSendingInterval);
		this.commandSender = new MonitorCommandSender(this.managerBrokerAddress);
		this.commandSender.init();
	}
	
	public Monitor(String managerBrokerAddress) {
		this(managerBrokerAddress, 1, 1);
	}
	
	public void setMonitorInterval(long second) {
		this.moniterInterval = second;
	}
	
	public void setMetaDataSendingInterval(long second) {
		this.metaDataSendingInterval = second;
	}
	
	/**
	 * Start the monitor.
	 */
	public void start() {
		assembleStaticMetaData();
		startMonitorWorkers();
		MetadataMessageSender metadataSender = new MetadataMessageSender();
		Thread metaDataSenderThread = new Thread(metadataSender);
		metaDataSenderThread.start();
		try {
			commandSender.registerToManager();
		} catch (JMSException e1) {
			e1.printStackTrace();
		}
	}
	
	/**
	 * Add a crawler to the monitor.
	 * @param crawler
	 */
	public void addCrawler(Crawler crawler) {
		CrawlerWorker crawlerWorker = new CrawlerWorker(crawler, this.moniterInterval * 1000);
		this.crawlers.put(crawler.getCrawlerName(), crawlerWorker);
	}
	
	/**
	 * Start all monitor workers to crawl the meta data.
	 */
	private void startMonitorWorkers() {
		for(Map.Entry<String, CrawlerWorker> entry : this.crawlers.entrySet()) {
			Thread thread = new Thread(entry.getValue());
			thread.start();
		}
	}
	
	/**
	 * Assemble the static metadata from each crawler.
	 */
	private void assembleStaticMetaData() {
		this.assembledDynamicMetaData.addProperty("machine-name", this.monitorIPAddress);
		for(Map.Entry<String, CrawlerWorker> entry : crawlers.entrySet()) {
			this.assembledStaticMetaData.add(entry.getKey(), entry.getValue().getCrawler().getStaticMetaData());
		}
	}
	
	public JsonObject getDynamicMetaData() {
		synchronized(assembledStaticMetaData) {
			return assembledDynamicMetaData;
		}
	}
	
	/**
	 * Assemble the meta data crawled by all the crawlers.
	 * @return
	 */
	public JsonObject assembleDynamicMetaData() {
		JsonObject newAssembledMetaData = new JsonObject();
		Date newDate = new Date();
		newAssembledMetaData.addProperty("timestamp", newDate.getTime() / 1000);
		for(Map.Entry<String, CrawlerWorker> entry : crawlers.entrySet()) {
			newAssembledMetaData.add(entry.getKey(), entry.getValue().getCrawler().getDynamicMetaData());
		}
		
		return newAssembledMetaData;
	}
	
	
	/**
	 * MonitorWork continuously fetch the dynamic metadata using a specified Crawler.
	 *
	 */
	public class CrawlerWorker implements Runnable{
		
		private Crawler crawler;
		private long sleepTimeInMillisecond;
		
		public CrawlerWorker(Crawler crawler, long sleepTimeInMillisecond) {
			this.crawler = crawler;
			this.sleepTimeInMillisecond = sleepTimeInMillisecond;
		}
		
		public Crawler getCrawler() {
			return crawler;
		}

		@Override
		public void run() {
			while(true) { 
				crawler.updateDynamicMetaData();
				try {
					Thread.sleep(sleepTimeInMillisecond);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

	}
	
	
	/**
	 * Send the dynamic meta data to broker periodically.
	 *
	 */
	class MetadataMessageSender implements Runnable{
		
		private String brokerAddress;
		private BrokerService broker;
		private MessageProducer metaDataProducer;
		private Session metaDataSession;
		
		public MetadataMessageSender() {
			this.brokerAddress = monitorIPAddress;
			createBroker();
			try {
				initMetaDataStreamService();
			} catch (JMSException e) {
				e.printStackTrace();
			}
		}
		
		private void createBroker() {
			broker = new BrokerService();
			broker.setBrokerName("testBroker");
			try {
				broker.setPersistent(false);
				broker.setUseJmx(false);
				broker.addConnector("tcp://" + this.brokerAddress + ":32100");
				broker.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		public void initMetaDataStreamService() throws JMSException {
			ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://" + this.brokerAddress + ":32100");
			Connection connection = connectionFactory.createConnection();
			connection.start();
			metaDataSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			                                                      
			Topic topic = metaDataSession.createTopic("metaData");
			metaDataProducer = metaDataSession.createProducer(topic);
			metaDataProducer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
		}
		
		public void sendMonitoredData() throws Exception{
			TextMessage metadataJsonMessage = metaDataSession.createTextMessage();
			JsonObject assembledDynamicMetaData = assembleDynamicMetaData();
			metadataJsonMessage.setText(assembledDynamicMetaData.toString());
			
			String correlateionID = UUID.randomUUID().toString();
			metadataJsonMessage.setJMSCorrelationID(correlateionID);
			this.metaDataProducer.send(metadataJsonMessage);
		}
		
		@Override
		public void run() {
			while(true) {
				try {
					sendMonitoredData();
					Thread.sleep(metaDataSendingInterval * 1000);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * MonitorCommandSender is in charge of sending command to manager.
	 *
	 */
	class MonitorCommandSender extends InitiativeCommandHandler {

		public MonitorCommandSender(String commandBrokerAddress) {
			super(commandBrokerAddress);
		}

		/**
		 * Register the monitor.
		 * @throws JMSException 
		 */
		public void registerToManager() throws JMSException {
			Out.println("In register to manager. Register to " + this.remoteBrokerAddress);
			TextMessage registerCommandMessage = commandServiceSession.createTextMessage();
			JsonObject commandJson = new JsonObject();
			commandJson.addProperty("type", "monitor-registration");
			commandJson.addProperty("machine-name", monitorIPAddress);
			String correlateionID = UUID.randomUUID().toString();
			registerCommandMessage.setJMSCorrelationID(correlateionID);
			registerCommandMessage.setJMSReplyTo(this.commandServiceTemporaryQueue);
			registerCommandMessage.setText(commandJson.toString());
			commandProducer.send(registerCommandMessage);
		}
		
		@Override
		public void onMessage(Message commandMessage) {
			if(commandMessage instanceof TextMessage) {
				try {
					String commandJson = ((TextMessage) commandMessage).getText();
					JsonObject jsonObj = (JsonObject)jsonParser.parse(commandJson);
					if(jsonObj.get("type").getAsString().equals("monitor-registration-response")) {
						Out.println("Registration successfully.");
					}
				} catch (JMSException e) {
					e.printStackTrace();
				}
				
			}
		}

	}
	
	
}
