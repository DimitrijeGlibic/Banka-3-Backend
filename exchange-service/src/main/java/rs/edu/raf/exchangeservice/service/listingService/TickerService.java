package rs.edu.raf.exchangeservice.service.listingService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rs.edu.raf.exchangeservice.domain.model.Exchange;
import rs.edu.raf.exchangeservice.domain.model.listing.Ticker;
import rs.edu.raf.exchangeservice.jacoco.ExcludeFromJacocoGeneratedReport;
import rs.edu.raf.exchangeservice.repository.ExchangeRepository;
import rs.edu.raf.exchangeservice.repository.listingRepository.TickerRepository;
import rs.edu.raf.exchangeservice.service.historyService.StockDailyService;
import rs.edu.raf.exchangeservice.service.historyService.StockIntradayService;
import rs.edu.raf.exchangeservice.service.historyService.StockMonthlyService;
import rs.edu.raf.exchangeservice.service.historyService.StockWeeklyService;
import rs.edu.raf.exchangeservice.service.myListingService.MyStockService;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class TickerService {
    private final ExchangeRepository exchangeRepository;
    private final TickerRepository tickerRepository;
    private final StockService stockService;
    private final OptionService optionService;
    private final ForexService forexService;
    private final StockIntradayService stockIntradayService;
    private final StockDailyService stockDailyService;
    private final StockWeeklyService stockWeeklyService;
    private final StockMonthlyService stockMonthlyService;
    private final MyStockService myStockService;
    private final FutureService futureService;
    //private final String TickerURL = "https://api.polygon.io/v3/reference/tickers?active=true&apiKey=RTKplv_CDK1Lh7kx0yPTPEsaqUy14wiT";

    public void loadData() throws JsonProcessingException {
        if(tickerRepository.count() > 0){
            tickerRepository.deleteAll();
        }

        Map<String, String> tickers = new HashMap<>();
        tickers.put("AAPL", "Apple Inc.");
        tickers.put("AMD", "Advanced Micro Devices");
        tickers.put("AMZN", "Amazon Inc.");
        tickers.put("IBM", "International Business Machines");
        tickers.put("NVDA", "Nvidia");
        tickers.put("MSFT", "Microsoft");
        tickers.put("TSLA", "Tesla Inc.");
        tickers.put("META", "Meta Inc.");
        tickers.put("GOOGL", "Google Inc.");
        tickers.put("INTC", "Intel");
        tickers.put("T", "AT&T Inc");
        tickers.put("F", "Ford");

        List<Exchange> exchanges = exchangeRepository.findAll();

        for (Map.Entry<String, String> entry : tickers.entrySet()) {
            Ticker ticker = new Ticker();
            ticker.setTicker(entry.getKey());
            ticker.setName(entry.getValue());

            Random random = new Random();
            int randomIndex = random.nextInt(exchanges.size());
            Exchange exchange = exchanges.get(randomIndex);
            ticker.setPrimaryExchange(exchange.getExchange());
            ticker.setCurrencyName("RSD");
//            ticker.setCurrencyName(exchange.getCurrency().toUpperCase());
            tickerRepository.save(ticker);
        }

        stockService.loadData();
        optionService.loadData();
        forexService.loadData();
        stockIntradayService.loadData();
        stockDailyService.loadData();
        stockWeeklyService.loadData();
        stockMonthlyService.loadData();
        futureService.loadData();
    }

    @ExcludeFromJacocoGeneratedReport
    public void addTicker(String ticker){
        String url = "https://api.polygon.io/v3/reference/tickers?ticker=" + ticker + "&apiKey=RTKplv_CDK1Lh7kx0yPTPEsaqUy14wiT";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .build();
        try{
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() == 200){
                Gson gson = new Gson();
                JsonObject jsonObject = gson.fromJson(response.body(), JsonObject.class);
                Ticker newTicker = new Ticker();
                newTicker.setTicker(ticker);
                newTicker.setName(jsonObject.get("results").getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString());
                newTicker.setCurrencyName("RSD");

                List<Exchange> exchanges = exchangeRepository.findAll();
                Random random = new Random();
                int randomIndex = random.nextInt(exchanges.size());
                Exchange exchange = exchanges.get(randomIndex);
                newTicker.setPrimaryExchange(exchange.getExchange());
                tickerRepository.save(newTicker);
            } else{
                System.out.println("Error: " + response.statusCode());
            }
        } catch (Exception e){
            System.out.println("Error while adding ticker");
        }
    }
}
