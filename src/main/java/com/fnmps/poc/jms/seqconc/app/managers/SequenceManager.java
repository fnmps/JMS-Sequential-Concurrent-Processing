package com.fnmps.poc.jms.seqconc.app.managers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;

import com.fnmps.poc.jms.seqconc.app.listeners.AbstractKeySequenceMessageListener;
import com.fnmps.poc.jms.seqconc.app.model.KeyAwareMessage;
import com.fnmps.poc.jms.seqconc.app.model.MessageKeyExtractor;
import com.fnmps.poc.jms.seqconc.app.model.SessionHolder;

public class SequenceManager {

	private static final Logger LOGGER = Logger.getLogger(SequenceManager.class.getName());

	private ThreadPoolExecutor executor;
	private ExecutorService mainExecutor;
	private MessageProcessor messageProcessorThread;

	private Connection connection;
	private AbstractKeySequenceMessageListener listener;
	private String queueName;
	private MessageKeyExtractor keyExtractor;

	private List<SessionHolder> sessionPool = new CopyOnWriteArrayList<>();

	public AbstractKeySequenceMessageListener getListener() {
		return listener;
	}

	public SequenceManager(String queueName, ConnectionFactory connectionFactory,
			AbstractKeySequenceMessageListener listener, MessageKeyExtractor keyExtractor) throws JMSException {
		this.queueName = queueName;
		this.connection = connectionFactory.createConnection();
		this.listener = listener;
		this.keyExtractor = keyExtractor;
		start();
	}

	public void start() throws JMSException {
		connection.start();
		mainExecutor = Executors.newSingleThreadExecutor();
		executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		messageProcessorThread = new MessageProcessor(listener, executor);
		mainExecutor.execute(messageProcessorThread);
	}

	public void stop() throws JMSException {
		try {
			messageProcessorThread.shutdown();
			mainExecutor.shutdown();
			mainExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS);
			executor.shutdown();
			executor.awaitTermination(5000, TimeUnit.MILLISECONDS);
			connection.stop();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void shutdown() throws JMSException {
		stop();
		listener.shutdown();
		executor.shutdownNow();
		mainExecutor.shutdownNow();
		for (SessionHolder session : sessionPool) {
			try {
				session.getSession().close();
			} catch (IllegalStateException | JMSException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}
		}
		sessionPool.clear();
		connection.close();
	}

	/**
	 * Receives the messages in a sequential manner. The onMessage of the listener
	 * will return as soon as the next message can be received
	 * 
	 * The next message will use a separate session so the acknowledgment and commit
	 * of the message that is still in processing is not affected
	 *
	 */
	class MessageProcessor implements Runnable {

		private AbstractKeySequenceMessageListener messageListener;
		private ExecutorService executor;
		private MessageConsumer currentConsumer;
		private Session currentSession;
		volatile boolean shouldShutdown = false;

		public MessageProcessor(AbstractKeySequenceMessageListener messageProcessor, ExecutorService executor) {
			this.messageListener = messageProcessor;
			this.executor = executor;
		}

		@Override
		public void run() {
			try {
				while (!executor.isShutdown() && !shouldShutdown && !Thread.currentThread().isInterrupted()) {
					SessionHolder sessionHolder = getAvailableSession();
					currentSession = sessionHolder.getSession();
					receiveAndProcessMessage(sessionHolder);
				}
			} catch (Exception e) {
				try {
					LOGGER.log(Level.SEVERE, e.getMessage(), e);
					shutdown();
				} catch (JMSException e1) {
					LOGGER.log(Level.SEVERE, e1.getMessage(), e1);
				}
			}
		}

		private void receiveAndProcessMessage(SessionHolder sessionHolder) throws JMSException {
			try {
				currentConsumer = currentSession.createConsumer(currentSession.createQueue(queueName));
				Message message = currentConsumer.receive();
				if (message != null) {
					String key = keyExtractor.extractKey(message);
					messageListener.onMessage(new KeyAwareMessage(message, key), sessionHolder);
				}
				currentConsumer.close();
			} catch (JMSException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
				currentSession.rollback();
				currentSession.close();
			}
		}

		public void shutdown() throws JMSException {
			shouldShutdown = true;
			if (currentSession != null) {
				currentSession.close();
			}
			if (currentConsumer != null) {
				currentConsumer.close();
			}
		}

		/**
		 * Fetches an existing unused session If none exists creates it and adds it to
		 * the pool
		 * 
		 * (this should not be needed if application server already has configured a JMS
		 * session pool)
		 * 
		 * @return
		 * @throws JMSException
		 */
		private synchronized SessionHolder getAvailableSession() throws JMSException {
			SessionHolder result = null;
			for (SessionHolder sessionHolder : sessionPool) {
				if (sessionHolder.isAvailable()) {
					result = sessionHolder;
				}
			}

			if (result == null) {
				LOGGER.info("No session available! Creating new session...");
				Session session = connection.createSession(Session.SESSION_TRANSACTED);
				result = new SessionHolder(session);
				result.setAvailable(false);
				sessionPool.add(result);
				LOGGER.log(Level.INFO, "Session created! Number of sessions is {0}", sessionPool.size());
			} else {
				LOGGER.info("Reusing existing session...");
				result.setAvailable(false);
			}
			return result;
		}
	}
}
