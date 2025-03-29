package morowayAppTeamplay;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import morowayAppTeamplay.scheme.SetGameJson;

@ServerEndpoint(value = "/moroway-app-java/")
public class Client {

	private Game game;

	private final Gson gson = new Gson();

	int id;
	private boolean locomotive = false;

	String name;

	private Session session;

	private void closeSession() {
		closeSession(session);
	}

	private void closeSession(Session session) {
		if (session != null && session.isOpen()) {
			try {
				session.close();
			} catch (IOException e) {
				if (Globals.DEBUG) {
					e.printStackTrace();
				}
			}
		}
	}

	private void destroyGame(Game game) {
		if (game != null) {
			game.clearTimeouts();
			List<Session> sessions;
			synchronized (game.clients) {
				sessions = game.clients.stream().filter(c -> c != null).map(c -> c.session).toList();
				game.clients.clear();
			}
			synchronized (game.clientsActive) {
				game.clientsActive.clear();
			}
			synchronized (game.clientsSyncing) {
				game.clientsSyncing.clear();
			}
			synchronized (game.clientsPaused) {
				game.clientsPaused.clear();
			}
			synchronized (game.clientsPausedBy) {
				game.clientsPausedBy.clear();
			}
			synchronized (Globals.GAMES) {
				Globals.GAMES.remove(game);
			}
			game = null;
			sessions.stream().forEach(s -> {
				closeSession(s);
			});
		}
	}

	private String getKeyString(int length) {
		String alphabet = "abcdefghjkmnpqrstuvwxyz"; // without i, l and o to avoid confusion (I/l) and (0/O)
		double valueForNumber = 0.3;
		String key = "";
		for (int i = 0; i < length; i++) {
			double random = Math.random();
			if (random < valueForNumber) {
				key += (int) (random / valueForNumber * 10);
			} else {
				char toAdd = alphabet
						.charAt((int) ((random - valueForNumber) / (1 - valueForNumber) * alphabet.length()));
				if (Math.random() < 0.5) {
					toAdd = Character.toUpperCase(toAdd);
				}
				key += toAdd;
			}
		}
		return key;
	}

	@OnClose
	public void onClose() {
		synchronized (Globals.CLIENTS) {
			Globals.CLIENTS.remove(this);
		}
		final Game gameLocal = game;
		if (gameLocal != null) {
			if (locomotive || gameLocal.clients.size() <= 2
					|| gameLocal.clientsActive.size() != gameLocal.clients.size()) {
				destroyGame(gameLocal);
			} else {
				synchronized (gameLocal.clients) {
					gameLocal.clients.remove(this);
				}
				synchronized (gameLocal.clientsActive) {
					gameLocal.clientsActive.remove(this);
				}
				synchronized (gameLocal.clientsSyncing) {
					gameLocal.clientsSyncing.remove(this);
				}
				synchronized (gameLocal.clientsPaused) {
					gameLocal.clientsPaused.remove(this);
				}
				synchronized (gameLocal.clientsPausedBy) {
					if (gameLocal.clientsPausedBy.contains(this)) {
						gameLocal.clientsPausedBy.remove(this);
						if (gameLocal.clientsPausedBy.isEmpty()) {
							sendToClients(gson.toJson(new MessageOut(null, "resume")));
						}
					}
				}
				sendToClients(gson.toJson(new MessageOut(this, "leave", null, Globals.ERROR_LEVEL_WARNING)));
			}
		}
	}

	@OnError
	public void onError(Throwable t) {
		if (Globals.DEBUG) {
			t.printStackTrace();
		}
		closeSession();
	}

	@OnMessage
	public void onMessage(String message) {
		// Convert JSON-Message to Object
		try {
			MessageIn messageIn = gson.fromJson(message, MessageIn.class);
			if (processMessage(messageIn, game)) {
				return;
			}
			sendToClient(gson.toJson(new MessageOut(this, messageIn.mode, null, Globals.ERROR_LEVEL_ERROR)));
		} catch (JsonParseException e) {
			if (Globals.DEBUG) {
				e.printStackTrace();
			}
			sendToClient(gson.toJson(new MessageOut(this, null, null, Globals.ERROR_LEVEL_ERROR)));
		}
	}

	@OnOpen
	public void onOpen(Session session) {
		this.session = session;
		int newClientId = 0;
		synchronized (Globals.CLIENTS) {
			Collections.sort(Globals.CLIENTS, new Comparator<Client>() {
				@Override
				public int compare(Client c1, Client c2) {
					if(c1 == null) {
						return -1;
					}
					if(c2 == null) {
						return 1;
					}
					return c1.id < c2.id ? -1 : 1;
				}
			});
			Iterator<Client> iterator = Globals.CLIENTS.iterator();
			while (iterator.hasNext()) {
				Client current = iterator.next();
				if (current != null && current.id > newClientId) {
					break;
				} else {
					newClientId++;
				}
			}
			id = newClientId;
			Globals.CLIENTS.add(this);
		}
	}

	private boolean processMessage(MessageIn messageIn, Game game) {
		switch (messageIn.mode) {
		case "hello":
			// Client first calls
			final double version = Double.parseDouble(messageIn.data);
			if (version < Globals.VERSION_MIN) {
				return false;
			}
			final int versionErrorLevel;
			if (version < Globals.VERSION_CURRENT) {
				versionErrorLevel = Globals.ERROR_LEVEL_WARNING;
			} else {
				versionErrorLevel = Globals.ERROR_LEVEL_OKAY;
			}
			sendToClient(gson.toJson(new MessageOut(this, messageIn.mode, null, versionErrorLevel)));
			return true;
		case "init":
			// Client sends player name
			final String pattern = "[^a-zA-Z0-9]";
			final String messageInName;
			if (messageIn.data == null || messageIn.data.replaceAll(pattern, "").equals("")) {
				messageInName = "anonymous";
			} else {
				messageInName = messageIn.data.replaceAll(pattern, "");
			}
			name = messageInName;
			sendToClient(gson.toJson(new MessageOut(this, messageIn.mode)));
			return true;
		case "connect":
			// Client requests to set up a new game
			locomotive = true;
			final String newGameKey = getKeyString((int) (Math.random() * 3 + 5));
			int newGameId = 0;
			synchronized (Globals.GAMES) {
				Collections.sort(Globals.GAMES, new Comparator<Game>() {
					@Override
					public int compare(Game g1, Game g2) {
						if(g1 == null) {
							return -1;
						}
						if(g2 == null) {
							return 1;
						}
						return g1.id < g2.id ? -1 : 1;
					}
				});
				Iterator<Game> iterator = Globals.GAMES.iterator();
				while (iterator.hasNext()) {
					Game current = iterator.next();
					if (current != null && current.id > newGameId) {
						break;
					} else {
						newGameId++;
					}
				}
				this.game = game = new Game(newGameId, newGameKey, this);
				Globals.GAMES.add(game);
			}
			sendToClient(gson
					.toJson(new MessageOut(this, messageIn.mode, gson.toJson(new SetGameJson(newGameId, newGameKey)))));
			return true;
		case "join":
			// Client requests to join an existing game
			try {
				SetGameJson gameToJoin = gson.fromJson(messageIn.data, SetGameJson.class);
				if (gameToJoin.key == null) {
					return false;
				}
				synchronized (Globals.GAMES) {
					game = Globals.GAMES.stream()
							.filter(g -> g != null && g.id == gameToJoin.id && g.key.equals(gameToJoin.key)).findFirst()
							.orElse(null);
				}
				if (game == null) {
					return false;
				}
				synchronized (game.clients) {
					if (game.clients.size() >= Globals.GAME_MAX_PLAYERS) {
						return false;
					}
				}
				synchronized (game.clientsActive) {
					if (!game.clientsActive.isEmpty()) {
						return false;
					}
				}
				this.game = game;
				final int currentGameSize;
				synchronized (game.clients) {
					game.clients.add(this);
					currentGameSize = game.clients.size();
				}
				sendToClients(gson.toJson(new MessageOut(this, messageIn.mode, Integer.toString(currentGameSize))));
			} catch (JsonParseException e) {
				if (Globals.DEBUG) {
					e.printStackTrace();
				}
				return false;
			}
			return true;
		case "start":
			// Client wants to start the game
			if (game == null) {
				return false;
			}
			final int currentGameSizeForActive;
			synchronized (game.clients) {
				currentGameSizeForActive = game.clients.size();
			}
			synchronized (game.clientsActive) {
				if (game.clientsActive.contains(this)) {
					return false;
				}
				game.clientsActive.add(this);
				if (game.clientsActive.size() == currentGameSizeForActive) {
					sendToClients(gson.toJson(new MessageOut(this, messageIn.mode, "run")));
				} else {
					sendToClients(gson.toJson(new MessageOut(this, messageIn.mode, "wait")));
				}
			}
			return true;
		case "action":
			// Client triggers an action
			if (game == null) {
				return false;
			}
			synchronized (game.clientsSyncing) {
				if (!game.clientsSyncing.isEmpty()) {
					return false;
				}
			}
			sendToClients(gson.toJson(new MessageOut(this, messageIn.mode, messageIn.data)));
			return true;
		case "sync-request":
			// Client requests to sync
			if (game == null || !locomotive) {
				return false;
			}
			synchronized (game.clientsSyncing) {
				if (!game.clientsSyncing.isEmpty()) {
					return false;
				}
				game.clearSyncTimeout();
				final Game gameLocal = game;
				game.syncTimeout = new Timer();
				game.syncTimeoutTask = new TimerTask() {
					@Override
					public void run() {
						if (gameLocal != null) {
							synchronized (gameLocal.clientsSyncing) {
								gameLocal.clientsSyncing.clear();
							}
							sendToClients(
									gson.toJson(new MessageOut(null, "sync-done", null, Globals.ERROR_LEVEL_WARNING)));
						}
					}
				};
				game.syncTimeout.schedule(game.syncTimeoutTask, 7500);
			}
			sendToClients(gson.toJson(new MessageOut(null, messageIn.mode, messageIn.data)));
			return true;
		case "sync-ready":
			// Client is ready to sync
			if (game == null) {
				return false;
			}
			final int currentGameSizeForSyncReady;
			synchronized (game.clients) {
				currentGameSizeForSyncReady = game.clients.size();
			}
			synchronized (game.clientsSyncing) {
				if (!game.clientsSyncing.contains(this)) {
					game.clientsSyncing.add(this);
				}
				if (game.clientsSyncing.size() == currentGameSizeForSyncReady) {
					sendToClients(gson.toJson(new MessageOut(null, messageIn.mode)));
				}
			}
			return true;
		case "sync-task":
			// Client sends sync task
			if (game == null || !locomotive) {
				return false;
			}
			final int currentGameSizeForSyncTask;
			synchronized (game.clients) {
				currentGameSizeForSyncTask = game.clients.size();
			}
			synchronized (game.clientsSyncing) {
				if (game.clientsSyncing.size() != currentGameSizeForSyncTask) {
					return false;
				}
			}
			sendToClients(gson.toJson(new MessageOut(null, messageIn.mode, messageIn.data)));
			return true;
		case "sync-done":
			// Client finished sync tasks (or sync is cancelled)
			if (game == null) {
				return false;
			}
			synchronized (game.clientsSyncing) {
				game.clientsSyncing.remove(this);
				if (game.clientsSyncing.isEmpty()) {
					game.clearSyncTimeout();
					sendToClients(gson.toJson(new MessageOut(null, messageIn.mode)));
				}
			}
			return true;
		case "pause-request":
			// Client requests to pause
			if (game == null) {
				return false;
			}
			synchronized (game.clientsPausedBy) {
				if (!game.clientsPausedBy.contains(this)) {
					game.clientsPausedBy.add(this);
				}
			}
			sendToClients(gson.toJson(new MessageOut(null, "pause")));
			return true;
		case "pause":
			// Client pauses game
			if (game == null) {
				return false;
			}
			synchronized (game.clientsPaused) {
				if (!game.clientsPaused.contains(this)) {
					game.clientsPaused.add(this);
				}
			}
			return true;
		case "resume-request":
			// Client requests to resume
			if (game == null) {
				return false;
			}
			synchronized (game.clientsPausedBy) {
				game.clientsPausedBy.remove(this);
				if (game.clientsPausedBy.isEmpty()) {
					sendToClients(gson.toJson(new MessageOut(null, "resume")));
				}
			}
			return true;
		case "resume":
			// Client resumes game
			if (game == null) {
				return false;
			}
			synchronized (game.clientsPaused) {
				game.clientsPaused.remove(this);
			}
			return true;
		case "chat-msg":
			// Client sends a chat message
			if (game == null) {
				return false;
			}
			sendToClients(gson.toJson(new MessageOut(this, "chat-msg",
					(messageIn.data.length() > 500) ? messageIn.data.substring(0, 500) : messageIn.data)));
			return true;
		}
		return false;
	}

	private void sendToClient(Client client, String message) {
		if (client == null) {
			return;
		}
		Session session = client.session;
		if (session != null && session.isOpen()) {
			try {
				session.getBasicRemote().sendText(message);
			} catch (IOException e) {
				if (Globals.DEBUG) {
					e.printStackTrace();
				}
			}
		}
	}

	private void sendToClient(String message) {
		sendToClient(this, message);
	}

	private void sendToClients(String message) {
		final Game gamelocal = game;
		if (gamelocal != null) {
			synchronized (gamelocal.clients) {
				gamelocal.clients.stream().forEach(c -> {
					sendToClient(c, message);
				});
			}
		}
	}

}
