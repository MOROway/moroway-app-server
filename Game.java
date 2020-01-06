package morowayAppTeamplay;

import java.util.ArrayList;

public class Game {
	public int gameId;
	public String gameKey;
	public int gameActive = 0;
	public int gamePaused = 0;
	public int gameSyncBreak = 0;
    public ArrayList<Sessions> users = new ArrayList<>();

	Game(int gameId, String gameKey){
		this.gameId = gameId;
		this.gameKey = gameKey;
	}
	
}
