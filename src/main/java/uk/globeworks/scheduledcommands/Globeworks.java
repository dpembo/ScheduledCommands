package uk.globeworks.scheduledcommands;

public class Globeworks {
    
    private static final String RESET = "\u001B[0m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";
    private static final String RED = "\u001B[31m";
    private static final String BROWN = "\u001B[33m";


    public static String logo(String pluginName, String version) {
        
        String logo = "\n" +
        GREEN + " _______ _____   " + BLUE + "_______ " + GREEN + "______ " + BLUE + "_______\n" +
        GREEN + "|     __|     |_|" + BLUE + "       |" + GREEN + "   __ \\" + BLUE + "    ___|\n" +
        GREEN + "|    |  |       |" + BLUE + "   -   |" + GREEN + "   __ <" + BLUE + "    ___|\n" +
        BROWN + "|_______|_______|" + BLUE + "_______|" + BROWN + "______/" + BLUE + "_______\n" +
        RED + " ________ _______ ______ __  __ _______\n" +
        RED + "|  |  |  |       |   __ \\  |/  |     __|\n" +
        RED + "|  |  |  |   -   |      <     <|__     |\n" +
        RED + "|________|_______|___|__|__|\\__|_______|\n" +
        YELLOW + "\n" +
        YELLOW + pluginName + " v" + version + "\n" +
        RESET;
        return logo;
    }
}
