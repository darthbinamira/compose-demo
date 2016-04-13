import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import twitter4j.ResponseList;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.TwitterObjectFactory;
import twitter4j.User;
import twitter4j.conf.ConfigurationBuilder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Controller
@EnableAutoConfiguration
public class Example {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Twitter twitter;

    @Value("${twitter.consumer.key}")
    private String consumerKey;

    @Value("${twitter.consumer.secret}")
    private String consumerSecret;

    @Value("${twitter.access.token}")
    private String accessToken;

    @Value("${twitter.access.token.secret}")
    private String accessSecret;

    public Example() {
    }

    @RequestMapping("/")
    public String index(@RequestParam(name = "screen_name", defaultValue = "twitter") String screenName, Model model) {
        model.addAttribute("statuses", fetchTwitterFeed(screenName));
        model.addAttribute("names", fetchTwitterScreenNames());
        model.addAttribute("screen_name", screenName);
        return "index";
    }


    public static void main(String[] args) throws Exception {
        SpringApplication.run(Example.class, args);
    }

    private List<String> fetchTwitterScreenNames() {
        final List<String> screenNames = new ArrayList<String>();

        getJdbcTemplate().query("select screen_name from users", new ResultSetExtractor<String>() {
            @Override
            public String extractData(ResultSet rs) throws SQLException, DataAccessException {
                // too lazy, let's do it here instead
                while (rs.next()) {
                    screenNames.add(rs.getString(1));
                }
                return null;
            }
        });

        return screenNames;
    }

    private List<Status> fetchTwitterFeed(final String screenName) {
        if (!getRedisTemplate().hasKey(screenName.toLowerCase())) {
            Integer countUser = getJdbcTemplate().queryForObject("select count(*) from users where screen_name = ?", Integer.class, screenName.toLowerCase());
            if (countUser > 0) {
                System.out.println("Fetching from DB.");
                getJdbcTemplate().query("select * from tweets where screen_name = ?", new ResultSetExtractor<String>() {
                    @Override
                    public String extractData(ResultSet rs) throws SQLException, DataAccessException {
                        // too lazy, let's do it here instead
                        StringBuilder sbStatusIds = new StringBuilder();
                        for (int i = 0; rs.next(); ++i) {
                            // collect status ids
                            if (i > 0) {
                                sbStatusIds.append(",");
                            }
                            sbStatusIds.append(rs.getLong(1));

                            // get raw json
                            getRedisTemplate().opsForValue().set(rs.getString(1), rs.getString(3));
                        }
                        getRedisTemplate().opsForValue().set(screenName.toLowerCase(), sbStatusIds.toString());
                        return null;
                    }
                }, screenName.toLowerCase());
            } else {
                System.out.println("Fetching from Twitter.");
                // insert to db
                getJdbcTemplate().update("insert into users (screen_name) values (?)", screenName.toLowerCase());
                // not yet in cache, get from remote
                StringBuilder sbStatusIds = new StringBuilder();
                try {
                    ResponseList<Status> statuses = this.getTwitter4jApi().timelines().getUserTimeline(screenName.toLowerCase());
                    for (int i = 0, j = statuses.size(); i < j; ++i) {
                        Status status = statuses.get(i);
                        long statusId = status.getId();

                        // collect status ids
                        if (i > 0) {
                            sbStatusIds.append(",");
                        }
                        sbStatusIds.append(statusId);

                        // get raw json
                        String rawJSON = TwitterObjectFactory.getRawJSON(status);
                        getRedisTemplate().opsForValue().set(String.valueOf(statusId), rawJSON);

                        // insert to db
                        getJdbcTemplate().update("insert into tweets (id, screen_name, raw_json) values (?, ?, ?)", statusId, screenName.toLowerCase(), rawJSON);
                    }
                } catch (TwitterException e) {
                    e.printStackTrace();
                }

                getRedisTemplate().opsForValue().set(screenName.toLowerCase(), sbStatusIds.toString());
            }

        }

        List<Status> statuses = new ArrayList<Status>();

        String statusIds = getRedisTemplate().opsForValue().get(screenName.toLowerCase());
        String[] statusIdsArray = statusIds.split(",");
        for (String statusId : statusIdsArray) {
            try {
                String rawJSON = getRedisTemplate().opsForValue().get(statusId);
                Status status = TwitterObjectFactory.createStatus(rawJSON);
                statuses.add(status);
            } catch (TwitterException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return statuses;
    }

    public JdbcTemplate getJdbcTemplate() {
        return this.jdbcTemplate;
    }

    public StringRedisTemplate getRedisTemplate() {
        return this.redisTemplate;
    }

    public Twitter getTwitter4jApi() {
        if (this.twitter == null) {
            ConfigurationBuilder cb = new ConfigurationBuilder();
            cb.setDebugEnabled(true)
                    .setJSONStoreEnabled(true)
                    .setOAuthConsumerKey(this.consumerKey)
                    .setOAuthConsumerSecret(this.consumerSecret)
                    .setOAuthAccessToken(this.accessToken)
                    .setOAuthAccessTokenSecret(this.accessSecret);
            TwitterFactory tf = new TwitterFactory(cb.build());
            this.twitter = tf.getInstance();
        }
        return this.twitter;
    }
}
