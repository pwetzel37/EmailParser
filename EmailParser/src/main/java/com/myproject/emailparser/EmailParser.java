/**
 * Parses emails using the Gmail API, then appends .csv attachments 
 *  to existing Google Drive files based on attachment names.
 *
 * @author Patrick Wetzel (2021)
 */

package com.myproject.emailparser;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

public class EmailParser {
    
    public static final String FILEID_DCM = "";
    public static final String FILEID_EXTRA_DCM = "";
    public static final String FILEID_DV = "";
    
    public static void main(String[] args) throws IOException, GeneralSecurityException {
        
        List<String> dcmList = GmailMethods.getMessages("DCM");
        for (String dcmString : dcmList) {
            checkLinesDcm(dcmString);
        }
        
        List<String> xlsxList = GmailMethods.getMessages(".xlsx");
        for (String xlsxString : xlsxList) {
            checkLinesDcm(xlsxString);
        }
        
        List<String> dvList = GmailMethods.getMessages("DV");
        for (String dvString : dvList) {
            checkLinesDv(dvString);
        }
        
    }
    
    /**
     * Checks a DV attachment string and appends new lines to an existing Google Drive file.
     */
    public static void checkLinesDv(String attachment) 
            throws GeneralSecurityException, IOException {
        
        // get existing file for comparing
        String dvFile = DriveMethods.getFile(FILEID_DV);
        
        // create string builders and split attachment into individual lines
        StringBuilder dvBuilder = new StringBuilder();
        String[] attachLines = attachment.split("\\n");
        
        // check each line to see if it needs appended to the existing file
        for (String line : attachLines) {
            
            // only check lines that have a date (no header/grand total lines)
            if (line.contains("202") && !line.contains("Grand")) {
                
                // reformat the date if needed
                line = reformatLine(line);
                
                if (!dvFile.contains(line)) {
                    // some lines already have the \r
                    if (!line.contains("\r")) {
                        dvBuilder.append(line).append("\r\n"); 
                    } else { dvBuilder.append(line).append("\n"); }
                }
            }
        }
        
        // create the new lines strings and check if the file needs updated
        String dvLines = dvBuilder.toString();
        checkForUpdate(dvLines, FILEID_DV);       
    }
    
    /**
     * Checks a DCM attachment string and appends new lines to an existing Google Drive file.
     */
    public static void checkLinesDcm(String attachment) 
            throws GeneralSecurityException, IOException {
        
        // get existing files for comparing
        String dcmFile = DriveMethods.getFile(FILEID_DCM);
        String extrasFile = DriveMethods.getFile(FILEID_EXTRA_DCM);
        
        // create string builders and split attachment into individual lines
        StringBuilder dcmBuilder = new StringBuilder();
        StringBuilder extrasBuilder = new StringBuilder();
        String[] attachLines = attachment.split("\\n");
        
        // check each line to see if it needs appended to the existing file
        for (String line : attachLines) {
            
            // only check lines that have a date (no header/grand total lines)
            if (line.contains("202") && !line.contains("Grand")) {
                
                // reformat the date if needed
                line = reformatLine(line);
                
                // if there are 13 columns, it must go in the extras file
                if (line.split(",").length == 13) {
                    if (!extrasFile.contains(line)) {
                        extrasBuilder.append(line).append("\r\n");
                    }
                } else if (line.split(",").length == 7) {
                    if (!dcmFile.contains(line)) {
                        dcmBuilder.append(line).append("\r\n");
                    }
                }
            }
        }
        
        // create the new lines strings and check if the file needs updated
        String dcmLines = dcmBuilder.toString();
        String extrasLines = extrasBuilder.toString();
        checkForUpdate(dcmLines, FILEID_DCM);
        checkForUpdate(extrasLines, FILEID_EXTRA_DCM);        
    }
    
    /**
     * Checks if the given file needs updated, if so it appends the new lines.
     */
    public static void checkForUpdate(String newLines, String fileId) 
            throws GeneralSecurityException, IOException {
        
        StringBuilder builder = new StringBuilder();
        String existingFile = DriveMethods.getFile(fileId);
        if (!newLines.isEmpty()) {
            builder.append(existingFile).append(newLines);
            DriveMethods.updateFile(builder.toString(), fileId);
        } else { System.out.println("No new entries"); }
    }
    
    /**
     * Checks if the given line needs reformatted from mm/dd/yyyy to yyyy-mm-dd.
     * @return the given line if unchanged, or the modified line
     */
    public static String reformatLine(String line) {
        StringBuilder dateBuilder = new StringBuilder();
        String[] columns = line.split(",");
        if (columns[0].contains("/")) {
            String[] dateArray = columns[0].split("/");
            
            // if there is only 1 digit in the month or day, append 0 first
            // year
            dateBuilder.append(dateArray[2]).append("-");
            
            // month
            if (dateArray[0].length() == 1) {
                dateBuilder.append("0").append(dateArray[0]).append("-");
            } else { dateBuilder.append(dateArray[0]).append("-"); }
            
            // day
            if (dateArray[1].length() == 1) {
                dateBuilder.append("0").append(dateArray[1]);
            } else { dateBuilder.append(dateArray[1]); }
            
            // create new line with fixed date
            StringBuilder lineBuilder = new StringBuilder();
            lineBuilder.append(dateBuilder.toString()).append(",");
            for (int i = 1; i < columns.length; i++) {
                lineBuilder.append(columns[i]);
                if (!(i == (columns.length - 1))) {
                    lineBuilder.append(",");
                }
            }
            line = lineBuilder.toString();
        }
        return line;
    }
}