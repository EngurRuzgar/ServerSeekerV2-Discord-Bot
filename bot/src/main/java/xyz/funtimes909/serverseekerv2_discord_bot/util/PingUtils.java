package xyz.funtimes909.serverseekerv2_discord_bot.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xyz.funtimes909.serverseekerv2_core.records.Mod;
import xyz.funtimes909.serverseekerv2_core.records.Player;
import xyz.funtimes909.serverseekerv2_core.records.Server;
import xyz.funtimes909.serverseekerv2_core.types.AnsiCodes;
import xyz.funtimes909.serverseekerv2_core.util.MotdUtils;
import xyz.funtimes909.serverseekerv2_discord_bot.Main;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PingUtils {
    private static final StringBuilder motd = new StringBuilder();
    private static final byte[] REQUEST = new byte[] {
            8, // Size: Amount of proceeding bytes [varint]
            0, // ID: Has to be 0
            0, // Protocol Version: Can be anything as long as it's a valid varint
            2, 0x3A, 0x33, // Address: As it is indexed with a varint to state it's size, we can just skip sending anything by setting it's size to 0
            0, 0, // Port: Can be anything (Notchian servers don't use this)
            1, // Next State: 1 for status, 2 for login. Therefore, has to be 1
            1, // Size
            0, // ID
    };

    public static Server parse(String address, short port) {
        try (Socket conn = new Socket()) {
            conn.setSoTimeout(4000);
            conn.connect(new  InetSocketAddress(address, port), 4000);
            String json = ping(conn);
            if (json == null) return null;

            JsonObject parsedJson = JsonParser.parseString(json).getAsJsonObject();
            String version = null;
            Boolean cracked = null;
            Integer protocol = null;
            Integer fmlNetworkVersion = null;
            Integer maxPlayers = null;
            Integer onlinePlayers = null;
            List<Player> playerList = new ArrayList<>();
            List<Mod> modsList  = new ArrayList<>();

            // Minecraft server information
            if (parsedJson.has("version")) {
                version = parsedJson.get("version").getAsJsonObject().get("name").getAsString();
                protocol = parsedJson.get("version").getAsJsonObject().get("protocol").getAsInt();
            }

            // Description can be either an object or a string
            if (parsedJson.has("description")) {
                if (parsedJson.get("description").isJsonObject()) {
                    MotdUtils.buildMOTD(parsedJson.get("description").getAsJsonObject(), 10, motd);
                } else {
                    motd.append(parsedJson.get("description").getAsString());
                }
            }

            // Forge servers send back information about mods
            if (parsedJson.has("forgeData")) {
                fmlNetworkVersion = parsedJson.get("forgeData").getAsJsonObject().get("fmlNetworkVersion").getAsInt();
                if (parsedJson.get("forgeData").getAsJsonObject().has("mods")) {
                    for (JsonElement modJson : parsedJson.get("forgeData").getAsJsonObject().get("mods").getAsJsonArray().asList()) {
                        String modId = modJson.getAsJsonObject().get("modId").getAsString();
                        String modmarker = modJson.getAsJsonObject().get("modmarker").getAsString();

                        Mod mod = new Mod(modId, modmarker);
                        modsList.add(mod);
                    }
                }
            }

            // Check for players
            if (parsedJson.has("players")) {
                maxPlayers = parsedJson.get("players").getAsJsonObject().get("max").getAsInt();
                onlinePlayers = parsedJson.get("players").getAsJsonObject().get("online").getAsInt();
                if (parsedJson.get("players").getAsJsonObject().has("sample")) {
                    for (JsonElement playerJson : parsedJson.get("players").getAsJsonObject().get("sample").getAsJsonArray().asList()) {
                        if (playerJson.getAsJsonObject().has("name") && playerJson.getAsJsonObject().has("id")) {
                            String name = playerJson.getAsJsonObject().get("name").getAsString();
                            String uuid = playerJson.getAsJsonObject().get("id").getAsString();

                            // Offline mode servers use v3 UUID's for players, while regular servers use v4, this is a really easy way to check if a server is offline mode
                            if (UUID.fromString(uuid).version() == 3) cracked = true;
                            long timestamp = System.currentTimeMillis() / 1000;
                            playerList.add(new Player(name, uuid, timestamp, timestamp));
                        }
                    }
                }
            }

            // Build server
            return new Server.Builder()
                    .setAddress(address)
                    .setPort(port)
                    .setLastSeen(System.currentTimeMillis() / 1000)
                    .setVersion(version)
                    .setProtocol(protocol)
                    .setFmlNetworkVersion(fmlNetworkVersion)
                    .setMotd(motd.toString())
                    .setIcon(parsedJson.has("favicon") ? parsedJson.get("favicon").getAsString() : null)
                    .setPreventsReports(parsedJson.has("preventsChatReports") ? parsedJson.get("preventsChatReports").getAsBoolean() : null)
                    .setEnforceSecure(parsedJson.has("enforcesSecureChat") ? parsedJson.get("enforcesSecureChat").getAsBoolean() : null)
                    .setCracked(cracked)
                    .setMaxPlayers(maxPlayers)
                    .setOnlinePlayers(onlinePlayers)
                    .setPlayers(playerList)
                    .setMods(modsList)
                    .build();

        } catch (IOException ignored) {}
        finally {
            motd.setLength(0);
        }
        return null;
    }

    public static String ping(Socket connection) {
        try (
                OutputStream out = connection.getOutputStream();
                InputStream in = connection.getInputStream();
                connection;
        ) {
            out.write(REQUEST);
            // Skip the first varint which indicates the total size of the packet.
            // Later we properly read a varint that contains the length of the json, so we use that
            for (byte i = 0; i < 5; i ++)
                if ((((byte) in.read()) & 0b10000000) == 0)
                    break;
            // Read the packet id, which should always be 0
            in.read();
            // Properly read the varint. This one contains the length of the following string
            int json_length = VarInt.decode_varint(in);
            // Finally read the bytes
            byte[] status = in.readNBytes(json_length);
            return new String(status);
        } catch (Exception e) {
            return null;
        }
    }

    public static Server buildResultsToObject(ResultSet results) {
        try (results) {
            Server.Builder server = new Server.Builder();
            List<Player> players = new ArrayList<>();
            List<Mod> mods = new ArrayList<>();

            while (results.next()) {
                server.setAddress(results.getString("address"));
                server.setPort(results.getShort("port"));
                server.setMotd(results.getString("motd"));
                server.setVersion(results.getString("version"));
                server.setFirstSeen(results.getLong("firstseen"));
                server.setLastSeen(results.getLong("lastseen"));
                server.setProtocol(results.getInt("protocol"));
                server.setCountry(results.getString("country"));
                server.setAsn(results.getString("asn"));
                server.setReverseDns(results.getString("reversedns"));
                server.setOrganization(results.getString("organization"));
                server.setWhitelist((Boolean) results.getObject("whitelist"));
                server.setEnforceSecure((Boolean) results.getObject("enforceSecure"));
                server.setCracked((Boolean) results.getObject("cracked"));
                server.setPreventsReports((Boolean) results.getObject("preventsReports"));
                server.setMaxPlayers(results.getInt("maxPlayers"));
                server.setTimesSeen(results.getInt("timesSeen"));
                server.setFmlNetworkVersion(results.getInt("fmlnetworkversion"));

                if (results.getString("playername") != null) players.add(new Player(results.getString("playername"), results.getString("playeruuid"), results.getLong("lastseen"), System.currentTimeMillis() / 1000));
                if (results.getString("modid") != null) mods.add(new Mod(results.getString("modid"), results.getString("modmarker")));
            }

            server.setPlayers(players);
            server.setMods(mods);

            return server.build();
        } catch (SQLException e) {
            Main.logger.error("Failed to build a server object!", e);
            return null;
        }
    }

    public static String parseMOTD(String description) {
        StringBuilder motd = new StringBuilder();

        for (String line : description.split("§")) {
            if (line.isBlank() || !AnsiCodes.colors.containsKey(line.charAt(0))) {
                motd.append(line);
                continue;
            }

            int code = AnsiCodes.colors.get(line.charAt(0)).ansi;

            // Use 50 as a reset code
            if (code == 50) {
                motd.append("\u001B[0m").append(line.substring(1));
                continue;
            }

            motd.append(code < 5 ?
                    "\u001B[" + code + ";00m" + line.substring(1) :
                    "\u001B[0;" + code + "m" + line.substring(1)
            );
        }
        return motd.toString();
    }
}