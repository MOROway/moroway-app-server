package morowayAppTeamplay;

class MessageOut extends Message {

	int sessionId;
	String sessionName;

	int errorLevel = Globals.ERROR_LEVEL_OKAY;

	MessageOut(Client client, String mode) {
		this(client, mode, null);
	}

	MessageOut(Client client, String mode, String message) {
		if(client != null) {
			sessionId = client.id;
			sessionName = client.name;
		}
		this.mode = mode;
		this.data = message;
	}

	MessageOut(Client client, String mode, String message, int errorLevel) {
		this(client, mode, message);
		this.errorLevel = errorLevel;
	}
}