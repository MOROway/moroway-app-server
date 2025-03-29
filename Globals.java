package morowayAppTeamplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class Globals {
	static final List<Client> CLIENTS = Collections.synchronizedList(new ArrayList<>());
	static final List<Game> GAMES = Collections.synchronizedList(new ArrayList<>());

	static final int GAME_MAX_PLAYERS = 4;

	static final double VERSION_CURRENT = 10.3;
	static final double VERSION_MIN = 10.3;

	static final int ERROR_LEVEL_OKAY = 0;
	static final int ERROR_LEVEL_WARNING = 1;
	static final int ERROR_LEVEL_ERROR = 2;

	static final boolean DEBUG = false;

}
