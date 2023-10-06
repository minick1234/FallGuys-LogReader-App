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

    public static void main(String[] args) throws IOException {

        //The current line we are reading from the file.
        String currentLine;

        //when round is found - read the next 19 lines and associate that with round information.
        int roundInfoCounter = 10;

        boolean printRoundInfo = false;

        File DefaultDirectory = CheckForDefaultLogsStorageFolder();

        boolean AddNewSession = true;

        //TODO: NEED TO FIX AN ISSUE THAT WHEN WE START THE APPLICATION MID SESSION OF A GAME IT CREATES DUPLICATE FILES.
        //WHEN PROCESS STARTS AND WE START READING THE FILE SKIP AND GO TO THE END RIGHT AWAY FOR THE FIRST TIME TO MAKE SURE
        //ANY PREVIOUS STUFF IS NOT READ AND WE START RIGHT FROM THE BOTTOM

        //Also fix that when you are not qualified and continue spectating it keeps creating round info for you

        //find a way to also aggregate all the files for rounds into one at the end of the match

        //find a way to go over every single round after a match and add the missing info i cannot get at real time.

        //Keep the program running so we can keep rechecking till fallguys opens again.
        while (true) {

            File SessionFolderDirectory = null;
            File MatchFolderDirectory = null;
            boolean matchStarted = false;

            //Only continue checking this file if we are still playing fall guys.
            while (IsProcessStillRunning()) {

                //Add a new session
                if (AddNewSession) {
                    AddNewSession = false;
                    SessionFolderDirectory = CreateNewSessionFolder(DefaultDirectory, SessionFolderDirectory);
                }

                FileReader fr = PrepareReadFile();
                //Open buffered reader to read the information inside the file.
                BufferedReader br = new BufferedReader(fr);

                long fileLength = new File(pathToPlayerLog).length();

                if (fileLength > lastKnownLength) {
                    fr.skip(lastKnownLength);
                    File roundFile;

                    while ((currentLine = br.readLine()) != null) {
                        ++lineCount;

                        if (printRoundInfo && roundInfoCounter > 0) {
                            ReadRoundInfo(currentLine);
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
                            --roundInfoCounter;
                            printRoundInfo = true;
                            System.out.println(currentLine);
                        }

                        if (currentLine.contains("[ClientGlobalGameState] Client has been disconnected")) {
                            matchStarted = false;
                        }

                        if (currentLine.contains("[CreateLocalPlayerInstances] Added new player as Participant, player ID = ")) {
                            UpdatePlayerID(currentLine);
                        }
                    }
                    lastKnownLength = fileLength;
                }
                //If the file has not updated or changed in any way we print out this info.
                //     PrintOutEndInfo();

                //Let this loop sleep for 2 seconds before rechecking the file for new changes.
                try {
                    Thread.sleep(2000);
                } catch (IllegalArgumentException | InterruptedException e) {
                    System.out.println(e.getMessage());
                }

                //Close the file reader and buffered reader.
                br.close();
                fr.close();
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

    public static FileReader PrepareReadFile() {

        //Attempt to get the %AppData%
        try {
            pathToPlayerLog = System.getenv("AppData") + "\\..\\LocalLow\\Mediatonic\\FallGuys_client\\Player.log";
        } catch (SecurityException | NullPointerException e) {
            System.out.println("The environment path cannot be found");
        }

        //Find the file and begin the file reader.
        File file = new File(pathToPlayerLog);
        FileReader fr = null;
        try {
            fr = new FileReader(file);
        } catch (FileNotFoundException e) {
            System.out.println("The file has not been found!");
        }
        if (fr != null) {
            return fr;
        }
        return null;
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
    public static void ReadRoundInfo(String currentLine) {
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
            newRoundTextFile = new File(matchFolderDirectory.getPath() + "\\Round_1.txt");
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