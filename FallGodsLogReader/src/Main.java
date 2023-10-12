import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.SecurityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static int currentPlayerID = -1, currentPartyID = -1, gamesPlayed = 0, numberOfSessions = 0, numberOfMatches = 0, numberOfRounds = 0;
    private static String pathToPlayerLog;
    private static ArrayList<String> MapPlayedSoFarInOrder = new ArrayList<String>();
    private static long lastKnownLength = 0, lineCount = 0;

    private static int CurrRoundNumWriting = 0;

    public static void main(String[] args) throws IOException {

        //The current line we are reading from the file.
        String currentLine;

        //when round is found - read the next 19 lines and associate that with round information.
        int roundInfoCounter = 10;

        boolean printRoundInfo = false;

        File DefaultDirectory = CheckForDefaultLogsStorageFolder();

        boolean AddNewSession = true;
        File roundFile = null;

        //TODO: NEED TO FIX AN ISSUE THAT WHEN WE START THE APPLICATION MID SESSION OF A GAME IT CREATES DUPLICATE FILES.
        //WHEN PROCESS STARTS AND WE START READING THE FILE SKIP AND GO TO THE END RIGHT AWAY FOR THE FIRST TIME TO MAKE SURE
        //ANY PREVIOUS STUFF IS NOT READ AND WE START RIGHT FROM THE BOTTOM

        //Also fix that when you are not qualified and continue spectating it keeps creating round info for you

        //find a way to also aggregate all the files for rounds into one at the end of the match

        //find a way to go over every single round after a match and add the missing info i cannot get at real time.

        //Keep the program running so we can keep rechecking till fallguys opens again.

        boolean JustLaunchedFirstSkip = false;
        RandomAccessFile raf = null;

        while (true) {

            File SessionFolderDirectory = null;
            File MatchFolderDirectory = null;
            boolean matchStarted = false;

            boolean DisconnectedPrematurely = false;

            //Only continue checking this file if we are still playing fall guys.
            while (IsProcessStillRunning()) {

                //Add a new session
                if (AddNewSession) {
                    AddNewSession = false;
                    SessionFolderDirectory = CreateNewSessionFolder(DefaultDirectory, SessionFolderDirectory);
                }

                if (raf == null) {
                    PrepareReadFile();
                    raf = new RandomAccessFile(pathToPlayerLog, "r");
                }

                if (!JustLaunchedFirstSkip) {
                    raf.seek(raf.length());
                    JustLaunchedFirstSkip = true;
                    lastKnownLength = raf.length();
                }

                if (raf.length() > lastKnownLength) {
                    raf.seek(lastKnownLength);
                    while ((currentLine = raf.readLine()) != null) {
                        //count up the line count for debugging purposes.
                        ++lineCount;

                        if (currentLine.contains("[HazelNetworkTransport] Disconnect request received for connection 0. Reason: HazelNetworkTransport - Disconnect")) {
                            System.out.println("We have left the lobby.");

                            long savedNormalPosition = raf.getFilePointer();

                            for (int i = 0; i < 10; i++) {
                                String nextLine = raf.readLine();
                                if (nextLine != null) {
                                    if (nextLine.contains("[StateWaitingForRewards] Init: waitingForRewards = ")) {
                                        DisconnectedPrematurely = true;
                                        break;
                                    }
                                } else {
                                    break;
                                }
                            }

                            if (DisconnectedPrematurely) {
                                System.out.println("We have disconnected prematurely to the match ending/Or we were eliminated.");
                                DisconnectedPrematurely = false;
                                //If we leave the match early we want to be sure to stop the current match check.
                                matchStarted = false;

                                //Reset the round and match folder to null to allow for appropriate creation again.
                                MatchFolderDirectory = null;
                                roundFile = null;
                                DisconnectedPrematurely = false;
                                break;
                            }
                            raf.seek(savedNormalPosition);
                        }

                        if (printRoundInfo && roundInfoCounter > 0) {

                            ReadRoundInfo(currentLine, roundFile);

                            //if the match is not started by this point and we have actually got here it means we ran
                            //the program while the game was already in a match.
                            //therefore we need to check for if match has started
                            //and if its false, create a new match folder and new round info for these rounds

                            if (!matchStarted) {
                                System.out.println("Match has not started and we are here\n" +
                                        "This means we were mid match when we started the program");
                            }

                            roundInfoCounter--;
                        } else if (printRoundInfo) {
                            printRoundInfo = false;
                            roundInfoCounter = 10;
                        }

                        if (currentLine.contains("[StateConnectToGame] We're connected to the server!")) {
                            MatchFolderDirectory = CreateNewMatchFolder(SessionFolderDirectory);
                            matchStarted = true;
                        } else if (matchStarted) {
                            if (currentLine.contains("[StateGameLoading] Finished loading game level, assumed to be")) {
                                roundFile = CreateNewRoundFile(MatchFolderDirectory);
                                FileWriter writer = new FileWriter(roundFile);
                                try {
                                    writer.append("this is for an example only.");
                                } catch (IOException e) {
                                    System.out.println("An error occured writing to the file");
                                }
                                writer.close();
                            }
                        }
                        if (currentLine.contains("== [CompletedEpisodeDto] ==")) {
                            gamesPlayed++;
                        }

                        if (currentLine.contains("[Round")) {
                            String[] roundLineInTwo = currentLine.split("\\|");
                            System.out.println("RoundlineinTwo 0 : " + roundLineInTwo[0] + "\n RoundlineinTwo 1: " + roundLineInTwo[1]);
                            int RoundNum = Integer.parseInt(roundLineInTwo[0].split(" ")[1].trim());
                            String MapName = roundLineInTwo[1].trim().substring(0, roundLineInTwo[1].length() - 2);
                            CurrRoundNumWriting = RoundNum;
                            System.out.println("Map name is: " + MapName);
                            System.out.println("Current round number is : " + RoundNum);
                            roundInfoCounter--;
                            printRoundInfo = true;
                        }

                        if (currentLine.contains("[ClientGlobalGameState] Client has been disconnected")) {
                            matchStarted = false;
                        }

                        if (currentLine.contains("[CreateLocalPlayerInstances] Added new player as Participant, player ID = ")) {
                            UpdatePlayerID(currentLine);
                        }
                    }
                    lastKnownLength = raf.getFilePointer();
                }
                //If the file has not updated or changed in any way we print out this info.
                //     PrintOutEndInfo();

                //Close the file reader and buffered reader.
                raf.close();
                raf = null;
            }

            //At this point, the session has stopped, so reset the number of rounds and matches to 0, for the new session when it starts.
            numberOfMatches = 0;
            numberOfRounds = 0;

            lastKnownLength = 0;
            AddNewSession = true;

            //Let this loop sleep for 2 seconds before rechecking if the process is open.
            try {
                Thread.sleep(2000);
            } catch (IllegalArgumentException | InterruptedException e) {
                System.out.println(e.getMessage());
            }
        }

    }

    public static void PrepareReadFile() {
        //Attempt to get the %AppData%
        try {
            pathToPlayerLog = System.getenv("AppData") + "\\..\\LocalLow\\Mediatonic\\FallGuys_client\\Player.log";
        } catch (SecurityException | NullPointerException e) {
            System.out.println("The environment path cannot be found");
        }
    }

    //Checks for if the process is running, this determines if we should continue checking the file for updates.
    public static boolean IsProcessStillRunning() {

        String processName = "FallGuys_client_game.exe";

        ProcessBuilder fallGuysProcessBuilder = new ProcessBuilder("tasklist");

        Process fallGuysProcess = null;
        try {
            fallGuysProcess = fallGuysProcessBuilder.start();
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.out.println("Can't find the tasklist.");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(fallGuysProcess.getInputStream()));
        String lineRead;

        try {
            while ((lineRead = reader.readLine()) != null) {
                if (lineRead.contains(processName)) {
                    reader.close();
                    return true;
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.out.println("Currently no lines to read.");
        }

        System.out.println("The process is not running.");
        return false;
    }

    public static void PrintOutEndInfo() {
        System.out.println("The file size in mb: " + String.format("%.2f", new File(pathToPlayerLog).length() / (1.049 * Math.pow(10, 6))));
        System.out.println("The total amount of lines we read: " + lineCount);
        System.out.println("The total amount of games played this session is: " + gamesPlayed);
        System.out.println("The most recent playerid is : " + currentPlayerID);
    }

    //Reads the current lines round info and assigns it to the values for the correct object.
    public static void ReadRoundInfo(String currentLine, File currentRoundFile) {

        //Start printing out the information for the current round information at the end of the match

        System.out.println(currentLine);
    }

    //This returns the new player id and is only called if player id has changed in the logs.
    public static long UpdatePlayerID(String currentLine) {

        String[] splitParts = currentLine.split("player ID =");
        currentPlayerID = Integer.parseInt(splitParts[1].trim());
        return currentPlayerID;
    }

    public static boolean CheckIfFileOrPathExists(Path path) {
        if (Files.isDirectory(path)) {
            return true;
        } else if (Files.isRegularFile(path)) {
            return true;
        }

        System.out.println("This path does not exist");
        return false;
    }

    //Checks if the round that we want to write to exists as a file
    public boolean CheckIfRoundFileAlreadyExists(File matchFolderDirectory, int roundNumber) {
        File[] roundFiles = matchFolderDirectory.listFiles(File::isFile);
        File newRoundTextFile = null;

        if (roundFiles != null && roundFiles.length > 0) {
            for (File roundFile : roundFiles) {
                String roundFileWithExtension = roundFile.getName().trim().split("_")[1];
                if (roundNumber == Integer.parseInt(roundFileWithExtension.trim().split("\\.")[0])) {
                    System.out.println("Found a round file of the same round number.");
                    return true;
                }
            }
        } else {
            System.out.println("No files inside this match directory of type round.");
        }

        return false;
    }

    //Write the information to this file.
    public static void AppendToPreviousRoundFile(File matchFolderDirectory, int roundToAppendTo) {


    }

    public static File CreateNewRoundFile(File matchFolderDirectory) {
        File[] roundFiles = matchFolderDirectory.listFiles(File::isFile);
        File newRoundTextFile = null;
        if (roundFiles != null && roundFiles.length > 0) {
            for (File roundFile : roundFiles) {
                String roundFileWithExtension = roundFile.getName().trim().split("_")[1];
                numberOfRounds = Integer.parseInt(roundFileWithExtension.trim().split("\\.")[0]);

            }
            newRoundTextFile = new File(matchFolderDirectory.getPath() + "\\Round_" + ++numberOfRounds + ".txt");
            try {
                newRoundTextFile.createNewFile();

            } catch (IOException e) {
                System.out.println("There was a error creating the file.");
            }

        } else {
            newRoundTextFile = new File(matchFolderDirectory.getPath() + "\\Round_0.txt");
            try {
                newRoundTextFile.createNewFile();

            } catch (IOException e) {
                System.out.println("There was a error creating the file.");
            }
        }

        return newRoundTextFile;
    }


    public static File CreateNewMatchFolder(File writeSessionsFolder) {

        File[] matchFolders = writeSessionsFolder.listFiles(File::isDirectory);
        File newMatchFolder = null;
        if (matchFolders != null && matchFolders.length > 0) {
            for (File folder : matchFolders) {
                numberOfMatches = Integer.parseInt(folder.getName().split("_")[1]);
            }
            newMatchFolder = new File(writeSessionsFolder.getPath() + "\\Match_" + ++numberOfMatches);
            newMatchFolder.mkdir();

        } else {
            newMatchFolder = new File(writeSessionsFolder.getPath() + "\\Match_1");
            newMatchFolder.mkdir();
        }

        return newMatchFolder;
    }

    public static File CreateNewSessionFolder(File defaultDirectory, File writeSessions) {

        File[] folders = defaultDirectory.listFiles(File::isDirectory);
        if (folders != null && folders.length > 0) {
            for (File folder : folders) {
                numberOfSessions = Integer.parseInt(folder.getName().split("_")[1]);
            }
            writeSessions = new File(defaultDirectory.getPath() + "\\GameSession_" + ++numberOfSessions);
            writeSessions.mkdir();

        } else {
            writeSessions = new File(defaultDirectory.getPath() + "\\GameSession_1");
            writeSessions.mkdir();
        }

        return writeSessions;
    }

    public static File CheckForDefaultLogsStorageFolder() {
        //Make sure the default directory for where we store data exists and if not we then create it.
        File DefaultDirectory = new File("C:\\FallGuysTracker_Logs");
        if (!CheckIfFileOrPathExists(DefaultDirectory.toPath())) {
            DefaultDirectory.mkdir();
        }
        return DefaultDirectory;
    }


}