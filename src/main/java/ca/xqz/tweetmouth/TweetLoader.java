package ca.xqz.tweetmouth;

import com.google.gson.Gson;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import java.io.FileInputStream;
import java.io.IOException;

import java.net.InetAddress;
import java.net.UnknownHostException;

import java.util.List;

public class TweetLoader {
    private final static int DEFAULT_LOAD_SIZE = 1000;

    private Client client;
    private Gson gson;
    private String index;
    private String type;

    public TweetLoader(String host, int port, String index, String type) {
        client = ESUtil.getTransportClient(host, port);
        System.out.println("Client created");
        gson = new Gson();
        this.index = index;
        this.type = type;
        if (!indexExists())
            createIndex();
    }

    public static int getDefaultSize() {
        return DEFAULT_LOAD_SIZE;
    }

    // TODO: Add buffering if we hook this up to the Twitter stream
    public void loadTweets(List<Tweet> tweets) {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        for (Tweet tweet : tweets) {
            bulkRequest.add(new IndexRequest(index, type, Long.toString(tweet.getId()))
                    .source(gson.toJson(tweet)));
        }
        int count = 0;
        BulkResponse resp = bulkRequest.get();
        if (resp.hasFailures()) {
            for (BulkItemResponse r : resp.getItems()) {
                if (r.isFailed()) {
                    count ++;
                }
            }
            System.out.println(count + " failed");
        }
    }

    private boolean indexExists() {
        IndicesExistsResponse resp = client.admin().indices().exists(new IndicesExistsRequest(index)).actionGet();
        return resp.isExists();
    }

    private void createIndex() {
        System.out.println("Index doesn't exist; creating it");
        client.admin().indices().prepareCreate(index)
            .addMapping(type, "{\n" +
                        "    \"" + type + "\": {\n" +
                        "      \"properties\": {\n" +
                        "        \"createdAt\": {\n" +
                        "          \"type\": \"date\",\n" +
                        "          \"format\": \"" + Tweet.DATE_FORMAT + "\"" +
                        "        },\n" +
                        "        \"geoLocation\": {\n" +
                        "          \"type\": \"geo_point\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }")
            .get();
    }

    public void finalize() {
        client.close();
    }

    public static void main(String[] args) {
        Options options = new Options();

        Option hostOption = new Option("h", "host", true, "The hostname of the ES node");
        options.addOption(hostOption);

        Option portOption = new Option("p", "port", true, "The port of the ES node");
        portOption.setType(Number.class);
        options.addOption(portOption);

        Option sizeOption = new Option("s", "size", true, "The number of tweets in a bundle");
        sizeOption.setType(Number.class);
        options.addOption(sizeOption);

        CommandLineParser clParser = new PosixParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = clParser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("TweetLoader", options);
            System.exit(1);
        }

        String host = cmd.getOptionValue("host", ESUtil.getDefaultHost());
        int port = ESUtil.getDefaultPort();
        int loadSize = DEFAULT_LOAD_SIZE;

        try {
            if (cmd.hasOption("port")) {
                port = ((Number) cmd.getParsedOptionValue("port")).intValue();
            }
            if (cmd.hasOption("size")) {
                loadSize = ((Number) cmd.getParsedOptionValue("size")).intValue();
            }
        } catch (ParseException e) {
            System.err.println("Error parsing options: " + e);
            return;
        }

        TweetParser parser = new TweetParser();
        TweetLoader loader = new TweetLoader(host, port, "test_index", "tweet");

        System.out.println("Loading data");
        List<Tweet> tweets;
        int iter = 1;
        do {
            try {
                tweets = parser.getParsedTweets(loadSize);
            } catch (IOException e) {
                System.err.println("Failed to process tweets.");
                return;
            }
            if (tweets.size() <= 0)
                break;
            loader.loadTweets(tweets);
            System.out.println("Processed " + loadSize*(iter ++));
         } while (true);
    }
}
