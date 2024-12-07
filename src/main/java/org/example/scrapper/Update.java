package org.example.scrapper;

import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.sql.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class Update {

    private static final String OUTPUT_DIR = "scraped_pages"; // Directory for storing HTML files
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/web_scraper"; // PostgreSQL URL
    private static final String DB_USER = "postgres"; // Database username
    private static final String DB_PASSWORD = "1234"; // Database password

    /**
     * Scrapes a webpage, saves its HTML content, and discovers nested links.
     */
    public static void scrapeWebPage(Set<String> pagesDiscovered, List<String> pagesToScrape, Connection dbConnection) {
        if (!pagesToScrape.isEmpty()) {
            String url = pagesToScrape.remove(0); // Get the URL to scrape
            pagesDiscovered.add(url);

            Document doc;
            try {
                long startTime = System.currentTimeMillis();

                // Fetch the HTML content
                doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .get();

                // Save the HTML content
                savePageToFile(url, doc);

                // Extract domain name
                String domain = extractDomainName(url);

                // Extract links from the page
                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String linkUrl = link.absUrl("href");
                    if (!pagesDiscovered.contains(linkUrl) && !pagesToScrape.contains(linkUrl)) {
                        pagesToScrape.add(linkUrl);
                    }
                }

                long endTime = System.currentTimeMillis();
                long elapsedTime = endTime - startTime;

                // Save the scraping details to the database
                saveToDatabase(dbConnection, domain, url, startTime, endTime, elapsedTime, doc.outerHtml().length() / 1024.0);
                System.out.println("Scraped and saved: " + url);

            } catch (IOException e) {
                System.err.println("Failed to scrape: " + url + " -> " + e.getMessage() +" possibly not found or check the link ");
            }
        }
    }

    /**
     * Saves the HTML content of a webpage to a file.
     */
    private static void savePageToFile(String url, Document doc) throws IOException {
        String domain = extractDomainName(url);
        Path domainDir = Paths.get(OUTPUT_DIR, domain);

        // Ensure directory exists
        if (!Files.exists(domainDir)) {
            Files.createDirectories(domainDir);
        }

        String fileName = sanitizeFileName(url) + ".html";
        Path filePath = domainDir.resolve(fileName);

        Files.write(filePath, doc.outerHtml().getBytes());
        System.out.println("Saved to file: " + filePath.toAbsolutePath());
    }

    /**
     * Saves scraping details to the database.
     */
    private static void saveToDatabase(Connection connection, String websiteName, String linkName,
                                       long startTime, long endTime, long elapsedTime, double sizeKb) {
        String sql = "INSERT INTO scraped_links (website_name, link_name, download_start_time, " +
                "download_end_time, elapsed_time_ms, size_kb) VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, websiteName);
            stmt.setString(2, linkName);
            stmt.setTimestamp(3, new Timestamp(startTime));
            stmt.setTimestamp(4, new Timestamp(endTime));
            stmt.setLong(5, elapsedTime);
            stmt.setDouble(6, sizeKb);

            stmt.executeUpdate();
            System.out.println("Saved to database: " + linkName);
        } catch (SQLException e) {
            System.err.println("Failed to save to database: " + e.getMessage());
        }
    }

    /**
     * Extracts the domain name from a URL.
     */
    private static String extractDomainName(String url) {
        try {
            URL netUrl = new URL(url);
            return netUrl.getHost().replace("www.", "");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Sanitizes a file name by replacing invalid characters.
     */
    private static String sanitizeFileName(String url) {
        return url.replaceAll("[^a-zA-Z0-9]", "_");
    }

    public static void main(String[] args) throws InterruptedException {
        Scanner scanner = new Scanner(System.in);

        // Regex pattern to validate URLs
        String urlPattern = "^(https?://)?([\\w.-]+)+(:\\d+)?(/([\\w/_\\-.]*)?)?$";

        System.out.print("Enter the URL to scrape: ");
        String inputUrl = scanner.nextLine();

        // Validate the input URL
        if (!Pattern.matches(urlPattern, inputUrl)) {
            System.err.println("Invalid URL. Please provide a valid URL.");
            return;
        }

        // Ensure the URL starts with http:// or https://
        if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
            inputUrl = "http://" + inputUrl; // Default to http if the protocol is missing
        }



        Set<String> pagesDiscovered = Collections.synchronizedSet(new HashSet<>());
        List<String> pagesToScrape = Collections.synchronizedList(new ArrayList<>());
//        pagesToScrape.add("https://www.ycombinator.com");
//        pagesToScrape.add("https://wwww.minict.gov.rw");
        pagesToScrape.add(inputUrl);

        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            ExecutorService executorService = Executors.newFixedThreadPool(4);

            scrapeWebPage(pagesDiscovered, pagesToScrape, connection);

            int limit = 20; // Limit to the number of pages to scrape
            int i = 1;

            while (!pagesToScrape.isEmpty() && i < limit) {
                executorService.execute(() -> scrapeWebPage(pagesDiscovered, pagesToScrape, connection));
                TimeUnit.MILLISECONDS.sleep(200); // Delay to avoid overloading the server
                i++;
            }

            executorService.shutdown();
            executorService.awaitTermination(300, TimeUnit.SECONDS);

            System.out.println("Discovered pages:");
            pagesDiscovered.forEach(System.out::println);

        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
        }
    }
}
