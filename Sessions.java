package morowayAppTeamplay;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

@ServerEndpoint(value = "/moroway-app-java/")
public class Sessions {

	private Session session;
	private int sessionId;
	private String sessionName;
	private boolean locomotive = false;
	private boolean pausedBy = false;

	private Game game;

	private final Gson gson = new Gson();

	public Sessions() {
	}

	@OnOpen
	public void onOpen(Session session) {
		this.session = session;
		Collections.sort(Globals.SESSIONS, new Comparator<Sessions>() {
			@Override
			public int compare(Sessions s1, Sessions s2) {
				return s1.sessionId < s2.sessionId ? -1 : 1;
			}
		});
		int tempSessionId = 0;
		Iterator<Sessions> iterator = Globals.SESSIONS.iterator();
		while (iterator.hasNext()) {
			Sessions currentSession = iterator.next();
			if (currentSession.sessionId > tempSessionId) {
				break;
			} else {
				tempSessionId++;
			}
		}
		this.sessionId = tempSessionId;
		Globals.SESSIONS.add(this);
	}

	@OnMessage
	public void onMessage(String message) {

		// Convert JSON-Message to Object
		Message obj;
		try {
			obj = gson.fromJson(message, Message.class);
		} catch (JsonParseException e) {
			obj = new Message();
			obj.mode = "unknown";
		}
		obj.sessionId = this.sessionId;
		obj.sessionName = this.sessionName;

		switch (obj.mode) {
		// True, if client first calls
		case "hello":
			double version = Double.parseDouble(obj.message);
			if (version < Globals.VERSION_MIN) {
				obj.errorLevel = Globals.ERROR_LEVEL_ERROR;
			} else if (version < Globals.VERSION_CURRENT) {
				obj.errorLevel = Globals.ERROR_LEVEL_WARNING;
			} else {
				obj.errorLevel = Globals.ERROR_LEVEL_OKAY;
			}
			sendToClient(gson.toJson(obj));
			break;
		// True, if client sends player name
		case "init":
			String pattern = "[^a-zA-Z0-9]";
			if (obj.message == null || obj.message.replaceAll(pattern, "").equals("")) {
				obj.message = "anonymous";
			} else {
				obj.message = obj.message.replaceAll(pattern, "");
			}
			this.sessionName = obj.message;
			obj.sessionName = this.sessionName;
			obj.errorLevel = Globals.ERROR_LEVEL_OKAY;
			sendToClient(gson.toJson(obj));
			break;
		// True, if client requests to set up a new game
		case "connect":
			// Sort games and get smallest "free" game id
			Collections.sort(Globals.GAMES, new Comparator<Game>() {
				@Override
				public int compare(Game g1, Game g2) {
					return g1.gameId < g2.gameId ? -1 : 1;
				}
			});
			this.locomotive = true;
			int tempGameId = 0;
			Iterator<Game> iterator = Globals.GAMES.iterator();
			while (iterator.hasNext()) {
				Game currentGame = iterator.next();
				if (currentGame.gameId > tempGameId) {
					break;
				} else {
					tempGameId++;
				}
			}
			// Set game id and key
			obj.gameId = tempGameId;
			obj.gameKey = getKeyString((int)(Math.random()*3+5));
			// Add game
			Globals.GAMES.add(new Game(obj.gameId, obj.gameKey));
			initGame(obj);
			break;
		// True, if client requests to join a game
		case "join":
			if (obj.gameKey != null) {
				initGame(obj);
			}
			break;
		// True, if clients wants to start the game
		case "start":
			if (game != null) {
				game.gameActive++;
				if (game.gameActive == game.users.size()) {
					obj.message = "run";
				} else {
					obj.message = "wait";
				}
				sendToGameClients(gson.toJson(obj));
			}
			break;
		// True, if action is triggered
		case "action":
		// True, if client sends sync request
		case "sync-request":
		// True, if client sends sync task (after sync-ready)
		case "sync-task":
			sendToGameClients(gson.toJson(obj));
			break;
		// True, if client is sync ready (after sync-request)
		case "sync-ready":
			game.gameSyncBreak++;
			if (game.gameSyncBreak == game.users.size()) {
				sendToGameClients(gson.toJson(obj));
			} else {
				if(game.syncTimeout != null) {
					game.syncTimeout.cancel();
					game.syncTimeout.purge();
				}
				game.syncTimeout = new Timer();
				game.syncTimeout.schedule(new TimerTask() {
					@Override
					public void run() {
						Message abort = new Message();
						abort.mode = "sync-done";
						abort.message = "sync-cancel";
						abort.sessionId = sessionId;
						abort.sessionName = sessionName;
						abort.gameId = game.gameId;
						abort.gameKey = game.gameKey;
						abort.errorLevel = Globals.ERROR_LEVEL_WARNING;
						game.gameSyncBreak = 0;
						sendToGameClients(gson.toJson(abort));
					}
				}, 7500);
			}
			break;
		// True, if client finished sync (after sync-task)
		case "sync-done":
			game.gameSyncBreak--;
			if (game.gameSyncBreak == 0) {
				if(game.syncTimeout != null) {
					game.syncTimeout.cancel();
					game.syncTimeout.purge();
				}
				sendToGameClients(gson.toJson(obj));
			}
			break;
		// True, if client requests a pause
		case "pause-request":
			if(!this.pausedBy) {
				this.pausedBy = true;
				if(game.gamePausedByClients < game.users.size()) {
					game.gamePausedByClients++;
				}
				Message messagePause = new Message();
				messagePause.mode = "pause";
				sendToGameClients(gson.toJson(messagePause));
			}
			break;
		// True, if client pauses game
		case "pause":
			if(game.gamePaused < game.users.size()) {
				game.gamePaused++;
			}
			break;
		// True, if client requests a resume
		case "resume-request":
			if(this.pausedBy) {
				this.pausedBy = false;
				if(game.gamePausedByClients > 0) {
					game.gamePausedByClients--;
				}
				if(game.gamePausedByClients == 0) {
					Message messageResume = new Message();
					messageResume.mode = "resume";
					sendToGameClients(gson.toJson(messageResume));
				}
			}
			break;
		// True, if client resumes game
		case "resume":
			if(game.gamePaused > 0) {
				game.gamePaused--;
			}
			break;
		// True, if client send chat message
		case "chat-msg":
			if (obj.message.length() > 500) {
				obj.message = obj.message.substring(0, 500);
			}
			sendToGameClients(gson.toJson(obj));
			break;
		// True, if client sends unknown request
		default:
			obj.mode = "unknown";
			obj.errorLevel = Globals.ERROR_LEVEL_ERROR;
			sendToClient(gson.toJson(obj));
		}

	}

	@OnClose
	public void onClose() {
		destroy();
	}

	@OnError
	public void onError(Throwable t) {
		destroy();
	}

	private void destroy() {
		Globals.SESSIONS.remove(this);
		if (game != null) {
			game.users.remove(this);
			game.gameActive--;
			Message message = new Message();
			message.mode = "leave";
			if(game.users.size() < 2 || this.locomotive || game.gameActive != game.users.size()) {
				message.errorLevel = Globals.ERROR_LEVEL_ERROR;
				sendToGameClients(gson.toJson(message));
				if(game.syncTimeout != null) {
					game.syncTimeout.cancel();
					game.syncTimeout.purge();
				}
				Globals.GAMES.remove(game);
				game = null;
			} else {
				if(game.gamePaused > 0) {
					game.gamePaused--;
				}
				if(this.pausedBy) {
					if(game.gamePausedByClients > 0) {
						game.gamePausedByClients--;
					}
					if(game.gamePausedByClients == 0) {
						Message messageResume = new Message();
						messageResume.mode = "resume";
						sendToGameClients(gson.toJson(messageResume));
					}
				}
				message.sessionName = this.sessionName;
				message.errorLevel = Globals.ERROR_LEVEL_WARNING;
				sendToGameClients(gson.toJson(message));
			}
		}
	}

	private void sendToClient(String message) {
		if(session != null && session.isOpen()) {
			try {
				session.getBasicRemote().sendText(message);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void sendToGameClients(String message) {
		if (game != null && game.users != null) {
			synchronized(game.users) {
				Iterator<Sessions> iterator = game.users.iterator();
				while (iterator.hasNext()) {
					Sessions currentSession = iterator.next();
					if(currentSession.session != null && currentSession.session.isOpen()) {
						try {
							currentSession.session.getBasicRemote().sendText(message);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
	}

	private boolean initGame(Message obj) {
		Iterator<Game> iterator = Globals.GAMES.iterator();
		while (iterator.hasNext()) {
			Game currentGame = iterator.next();
			if (obj.gameId == currentGame.gameId && obj.gameKey.equals(currentGame.gameKey)) {
				if (currentGame.users.size() < Globals.GAME_MAX_PLAYERS && currentGame.gameActive == 0) {
					game = currentGame;
					game.users.add(this);
					// Send game key to requesting client
					obj.errorLevel = Globals.ERROR_LEVEL_OKAY;
					obj.message = Integer.toString(currentGame.users.size());
					sendToGameClients(gson.toJson(obj));
					return true;
				} else {
					break;
				}
			}
		}
		obj.errorLevel = Globals.ERROR_LEVEL_ERROR;
		sendToClient(gson.toJson(obj));
		return false;
	}

	private String getKeyString(int length) {
		String alphabet = "abcdefghjkmnpqrstuvwxyz"; //without i, l and o to avoid confusion (I/l) and (0/O)
		double valueForNumber = 0.3;
		String key = "";
		for(int i = 0; i < length; i++) {
			double random = Math.random();
			if (random < valueForNumber) {
				key += (int) (random * (1/valueForNumber) * 10);
			}  else {
				char toAdd = alphabet.charAt((int) (random * alphabet.length()));
				if(random-valueForNumber < (1-valueForNumber)*0.5) {
					toAdd = Character.toUpperCase(toAdd);
				}
				key += toAdd;
			}
		}
		return key;
	}

}
