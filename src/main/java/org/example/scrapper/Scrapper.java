package org.example.scrapper;

import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Scrapper {

    public static void scrapeWebPage(
            Set<String> pagesDiscovered,
            List<String> pagesToScrape
    ) {
        if (!pagesToScrape.isEmpty()) {
            // Retrieve the first URL to scrape
            String url = pagesToScrape.remove(0);
            pagesDiscovered.add(url);

            Document doc;
            try {
                // Fetch the HTML content of the page
                doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .get();

                // Save the HTML content to a file
                String fileName = "output_" + url.replaceAll("[^a-zA-Z0-9]", "_") + ".html";
                Files.write(Paths.get(fileName), doc.outerHtml().getBytes());
                System.out.println("Saved: " + fileName);

                // Extract all nested links
                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String pageUrl = link.absUrl("href");
                    if (!pagesDiscovered.contains(pageUrl) && !pagesToScrape.contains(pageUrl)) {
                        pagesToScrape.add(pageUrl);
                    }
                }

                // Log the completion of scraping the current page
                System.out.println(url + " -> page scraped");

            } catch (IOException e) {
                System.err.println("Failed to scrape: " + url);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // Initialize the set of discovered pages
        Set<String> pagesDiscovered = Collections.synchronizedSet(new HashSet<>());

        // Initialize the queue of pages to scrape
        List<String> pagesToScrape = Collections.synchronizedList(new ArrayList<>());
        pagesToScrape.add("https://www.ycombinator.com/");
        pagesToScrape.add("https://www.minict.gov.rw");

        // Initialize the ExecutorService for parallel scraping
        ExecutorService executorService = Executors.newFixedThreadPool(4);

        // Perform an initial scrape to bootstrap
        scrapeWebPage(pagesDiscovered, pagesToScrape);

        int i = 1; // Iteration counter
        int limit = 12; // Limit to the number of pages to scrape

        while (!pagesToScrape.isEmpty() && i < limit) {
            executorService.execute(() -> scrapeWebPage(pagesDiscovered, pagesToScrape));

            // Delay to avoid overloading the server
            TimeUnit.MILLISECONDS.sleep(200);

            i++;
        }

        // Wait for all tasks to complete
        executorService.shutdown();
        executorService.awaitTermination(300, TimeUnit.SECONDS);

        // Print all discovered URLs
        System.out.println("Discovered pages:");
        pagesDiscovered.forEach(System.out::println);
    }
}
