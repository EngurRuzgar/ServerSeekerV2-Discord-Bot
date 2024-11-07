package xyz.funtimes909.serverseekerv2_discord_bot.commands;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import xyz.funtimes909.serverseekerv2_discord_bot.Main;
import xyz.funtimes909.serverseekerv2_discord_bot.util.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Search {
    private static final Connection conn = DatabaseConnectionPool.getConnection();
    private static SlashCommandInteractionEvent event;
    private static ResultSet resultSet;
    public static HashMap<Integer, Server> searchResults = new HashMap<>();
    public static int rowCount;
    public static int page = 1;

    public static void search(SlashCommandInteractionEvent interactionEvent) {
        event = interactionEvent;
        if (BlacklistCheck.check(event.getUser().getId())) {
            event.reply("Sorry! You're not authorized to use this command!").queue();
        }

        if (event.getOptions().isEmpty()) {
            event.reply("You must provide some search queries!").queue();
            return;
        }

        event.deferReply().queue();
        buildQuery(event.getOptions());
    }

    public static void scrollResults(int direction, boolean firstRun) {
        try {
            int rowCount = 0;
            searchResults.clear();
            resultSet.relative(direction);
            while (rowCount < 5 && resultSet.next()) {
                String address = resultSet.getString("address");
                String country = resultSet.getString("country");
                String version = resultSet.getString("version");
                long timestamp = resultSet.getLong("lastseen");
                short port = resultSet.getShort("port");

                searchResults.put(rowCount + 1, new Server(address, country, version, timestamp, port));
                rowCount++;
            }

            MessageEmbed embed = SearchEmbedBuilder.parse(searchResults);
            List<ItemComponent> buttons = new ArrayList<>();

            // Add buttons for each returned server
            for (Map.Entry<Integer, Server> server : searchResults.entrySet()) {
                buttons.add(Button.success("SearchButton" + server.getKey(), String.valueOf(server.getKey())));
            }

            // Send a new message if it's the first interaction, edit the original if it's a new search page
            if (firstRun) {
                if (searchResults.keySet().size() < 5) {
                    event.getHook().sendMessageEmbeds(embed).addActionRow(buttons).queue();
                } else {
                    event.getHook().sendMessageEmbeds(embed).addActionRow(buttons).addActionRow(Button.primary("PagePrevious", Emoji.fromFormatted("U+2B05")), Button.primary("PageNext", Emoji.fromFormatted("U+27A1"))).queue();
                }
            } else {
                event.getHook().editOriginalEmbeds(embed).queue();
            }

        } catch (SQLException e) {
            Main.logger.error("Error while executing query!", e);
        }
    }

    private static void buildQuery(List<OptionMapping> options) {
        Map<String, OptionMapping> parameters = new HashMap<>();
        StringBuilder baseQuery = new StringBuilder("SELECT * FROM servers WHERE ");
        for (OptionMapping option : options) {
            switch (option.getName()) {
                case "description":
                    parameters.put("motd", option);
                    break;
                case "playercount":
                    parameters.put("onlineplayers", option);
                    break;
                case "hostname":
                    parameters.put("reversedns", option);
                    break;
                case "seenbefore", "seenafter":
                    parameters.put("lastseen", option);
                    break;
                case "full":
                    if (option.getAsBoolean()) {
                        baseQuery.append("onlinePlayers >= maxPlayers AND ");
                    } else {
                        baseQuery.append("onlinePlayers < maxPlayers AND ");
                    }
                    break;
                case "forge":
                    if (option.getAsBoolean()) {
                        baseQuery.append("fmlnetworkversion IS NOT NULL AND ");
                    } else {
                        baseQuery.append("fmlnetworkversion IS NULL AND ");
                    }
                    break;
                case "icon":
                    if (option.getAsBoolean()) {
                        baseQuery.append("icon IS NOT NULL AND ");
                    } else {
                        baseQuery.append("icon IS NULL AND ");
                    }
                    break;
                default:
                    parameters.put(option.getName(), option);
                    break;
            }
        }

        parameters.forEach((key, value) -> {
            switch (value.getName()) {
                case "seenbefore" -> baseQuery.append("lastseen <= ? AND ");
                case "seenafter" -> baseQuery.append("lastseen >= ? AND ");
                case "reversedns" -> baseQuery.append("hostname = ? AND ");
                default -> baseQuery.append(key).append(" = ? AND ");
            }
        });

        try {
            // Create statement and assign values
            baseQuery.replace(baseQuery.length() - 4, baseQuery.length(), "");
            Connection conn = DatabaseConnectionPool.getConnection();
            PreparedStatement statement = conn.prepareStatement(baseQuery.toString(), ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);

            int index = 1;
            for (Map.Entry<String, OptionMapping> option : parameters.entrySet()) {
                switch (option.getValue().getType()) {
                    case STRING -> statement.setString(index, option.getValue().getAsString());
                    case INTEGER -> statement.setInt(index, option.getValue().getAsInt());
                    case BOOLEAN -> statement.setBoolean(index, option.getValue().getAsBoolean());
                }
                index++;
            }

            // Execute query and count the rows
            long startTime = System.currentTimeMillis();
            resultSet = statement.executeQuery();
            long endTime = System.currentTimeMillis();
            Main.logger.debug("Search command took {}ms to execute!", (endTime - startTime));
            resultSet.last();
            rowCount = resultSet.getRow();
            if (rowCount == 0) event.getHook().sendMessage("No results!").queue();

            // Set position to the first result
            resultSet.beforeFirst();
            scrollResults(0, true);
        } catch (SQLException e) {
            Main.logger.error("Error while executing query!", e);
        }
    }

    public static void buttonEvent(int row) {
        try (Connection conn = DatabaseConnectionPool.getConnection()) {
            PreparedStatement statement = conn.prepareStatement("SELECT * FROM servers WHERE address = ? AND port = ?");

            statement.setString(1, searchResults.get(row).address());
            statement.setInt(2, searchResults.get(row).port());

            ResultSet resultSet = statement.executeQuery();
            MessageEmbed embed = ServerEmbedBuilder.build(resultSet);

            if (embed == null) {
                event.getHook().sendMessage("Something went wrong executing that command!").queue();
                return;
            }

            event.getHook().sendMessageEmbeds(embed).queue();
        } catch (SQLException e) {
            Main.logger.error("Error while executing query!", e);
        }

    }
}

