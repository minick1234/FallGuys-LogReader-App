import java.util.List;

/*



 */
class GameSessionStats {

    int wins = 0;
    int losses = 0;
    List<List<Match>> matchStreaks;
    private int currentStreak;
    private int highestStreak;
    private long priorStreakSize;

    //Again this should be a time value but will change this later.
    private long durationOfGameSession;

    public GameSessionStats() {

    }

}