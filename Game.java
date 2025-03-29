package morowayAppTeamplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

class Game {
	final List<Client> clients = Collections.synchronizedList(new ArrayList<>());
	final List<Client> clientsActive = Collections.synchronizedList(new ArrayList<>());
	final List<Client> clientsPaused = Collections.synchronizedList(new ArrayList<>());
	final List<Client> clientsPausedBy = Collections.synchronizedList(new ArrayList<>());
	final List<Client> clientsSyncing = Collections.synchronizedList(new ArrayList<>());

	final int id;
	final String key;

	Timer syncTimeout = new Timer();
	TimerTask syncTimeoutTask;

	Game(int gameId, String gameKey, Client client) {
		id = gameId;
		key = gameKey;
		clients.add(client);
	}

	void clearSyncTimeout() {
		if (syncTimeoutTask != null) {
			syncTimeoutTask.cancel();
		}
		if (syncTimeout != null) {
			syncTimeout.cancel();
			syncTimeout.purge();
		}
	}

	void clearTimeouts() {
		clearSyncTimeout();
	}

}
