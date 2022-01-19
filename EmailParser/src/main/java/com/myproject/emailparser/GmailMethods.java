/**
 * Methods to get email messages and attachments through the Google Gmail API.
 *
 * @author Patrick Wetzel (2021)
 */

package com.myproject.emailparser;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Base64;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class GmailMethods {

    private static final String APPLICATION_NAME = "Email Parser";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Arrays.asList(GmailScopes.MAIL_GOOGLE_COM, DriveScopes.DRIVE);
    private static final String CLIENT_ID = "";
    private static final String CLIENT_SECRET = "";

    /**
     * Creates an authorized Credential object.
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    public static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) 
            throws IOException {
        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, CLIENT_ID, CLIENT_SECRET, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }
    
    /**
     * Returns the attachment of the given file as a string to be sent to Google Drive.
     * @param filename the name of the file inside the email's attachment
     */
    public static List<String> getMessages(String filename) 
            throws IOException, GeneralSecurityException {
        
        List<String> attachmentList = new ArrayList<>();
        
        // build a new authorized API client service
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Gmail service = new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();

        // get the messages in the user's account
        String user = "me";
        ListMessagesResponse messagesResponse = service.users().messages().list(user).execute();
        List<Message> messagesList = messagesResponse.getMessages();
        
        if (!messagesList.isEmpty()) {
            // iterate each individual message
            for (Message messageinList : messagesList) {
                String messageId = messageinList.getId();
                Message message = service.users().messages().get(user, messageId).execute();
                
                // get the number of days between the email's date and now
                long dateLong = message.getInternalDate();
                LocalDate emailDate = Instant.ofEpochMilli(dateLong)
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                LocalDate todayDate = Instant.now()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                int daysBetween = todayDate.compareTo(emailDate);
                
                // if the email is 3 days or older, delete it
                if (daysBetween >= 3) {
                    service.users().messages().delete(user, messageId).execute();
                    System.out.println("Message deleted: " + messageId);
                } 
                // get message parts, if filename is found, add to attachment list
                else {
                    List<MessagePart> messageParts = message.getPayload().getParts();
                    for (MessagePart part : messageParts) {

                        StringBuilder builder = new StringBuilder();

                        if (part.getFilename().contains(filename)) {   
                            // get attachment into a base-64 string
                            String attachmentId = part.getBody().getAttachmentId();
                            String attachmentBase64 = service.users().messages().attachments()
                                    .get(user, messageId, attachmentId).execute().getData();

                            // decode base64 string into byte array
                            byte[] attachmentBytes = Base64.decodeBase64(attachmentBase64);

                            // deal with .zip files
                            if (part.getFilename().contains(".zip")) {
                                // get first ZipEntry from the bytes array, should only be 1 file per .zip
                                ZipInputStream zipStream = new ZipInputStream(
                                        new ByteArrayInputStream(attachmentBytes)
                                );
                                zipStream.getNextEntry();

                                // read from .csv file and create a string object
                                BufferedReader reader = new BufferedReader(new InputStreamReader(zipStream));
                                String line;
                                while ((line = reader.readLine()) != null) { 
                                    builder.append(line).append("\n");
                                }
                                attachmentList.add(builder.toString());
                            } 
                            // deal with .xlsx files
                            else if (part.getFilename().contains(".xlsx")) {
                                String xlsxString = parseXlsx(attachmentBytes);
                                attachmentList.add(xlsxString);
                            }
                            // deal with .csv files
                            else {
                                String csvString = new String(attachmentBytes);
                                attachmentList.add(csvString);
                            }
                        }
                    }
                }
            }
        } else { System.out.println("No messages found."); }
        
        return attachmentList;
    }
    
    /**
     * Takes the given byte[] array and uses the Apache POI API
     *  to turn each row entry into a csv String.
     * @param bytes the byte array returned from the Gmail API
     */
    public static String parseXlsx(byte[] bytes) throws FileNotFoundException, IOException {
        // save the bytes array into an .xlsx file
        File file = java.io.File.createTempFile("temp", ".xlsx");
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(bytes);
        }
        FileInputStream fiStream = new FileInputStream(file);
                            
        // use apache poi API to parse .xlsx file
        Workbook workbook = new XSSFWorkbook(fiStream);
        Sheet sheet = workbook.getSheetAt(0);
                            
        // iterate each row in the file
        StringBuilder builder = new StringBuilder();
        Iterator<Row> rowIterator = sheet.iterator();
        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();
            Iterator<Cell> cellIterator = row.iterator();
            StringBuilder entryBuilder = new StringBuilder();
                                
            // iterate each cell in the row and create a csv entry
            while (cellIterator.hasNext()) {
                Cell cell = cellIterator.next();
                String value = "";
                switch (cell.getCellType()) {
                    case STRING:
                        value = cell.getStringCellValue();
                        break;
                    case NUMERIC:
                        value = String.valueOf(cell.getNumericCellValue());
                        break;
                }
                entryBuilder.append(value);
                if (cellIterator.hasNext()) {
                    entryBuilder.append(",");
                }
            }
            builder.append(entryBuilder.toString()).append("\n");
        }
        return builder.toString();
    }
}