package de.diddiz.LogBlock;

import static de.diddiz.LogBlock.config.Config.askRollbackAfterBan;
import static de.diddiz.LogBlock.config.Config.autoClearLogDelay;
import static de.diddiz.LogBlock.config.Config.checkVersion;
import static de.diddiz.LogBlock.config.Config.delayBetweenRuns;
import static de.diddiz.LogBlock.config.Config.enableAutoClearLog;
import static de.diddiz.LogBlock.config.Config.isLogging;
import static de.diddiz.LogBlock.config.Config.load;
import static de.diddiz.LogBlock.config.Config.logPlayerInfo;
import static de.diddiz.LogBlock.config.Config.password;
import static de.diddiz.LogBlock.config.Config.toolsByType;
import static de.diddiz.LogBlock.config.Config.url;
import static de.diddiz.LogBlock.config.Config.useBukkitScheduler;
import static de.diddiz.LogBlock.config.Config.user;
import static de.diddiz.util.Utils.download;
import static org.bukkit.Bukkit.getPluginManager;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import de.diddiz.LogBlock.config.Config;
import de.diddiz.LogBlock.listeners.BanListener;
import de.diddiz.LogBlock.listeners.BlockBreakLogging;
import de.diddiz.LogBlock.listeners.BlockBurnLogging;
import de.diddiz.LogBlock.listeners.BlockPlaceLogging;
import de.diddiz.LogBlock.listeners.ChatLogging;
import de.diddiz.LogBlock.listeners.ChestAccessLogging;
import de.diddiz.LogBlock.listeners.EndermenLogging;
import de.diddiz.LogBlock.listeners.ExplosionLogging;
import de.diddiz.LogBlock.listeners.FluidFlowLogging;
import de.diddiz.LogBlock.listeners.InteractLogging;
import de.diddiz.LogBlock.listeners.KillLogging;
import de.diddiz.LogBlock.listeners.LeavesDecayLogging;
import de.diddiz.LogBlock.listeners.PlayerInfoLogging;
import de.diddiz.LogBlock.listeners.SignChangeLogging;
import de.diddiz.LogBlock.listeners.SnowFadeLogging;
import de.diddiz.LogBlock.listeners.SnowFormLogging;
import de.diddiz.LogBlock.listeners.StructureGrowLogging;
import de.diddiz.LogBlock.listeners.ToolListener;
import de.diddiz.util.MySQLConnectionPool;

public class LogBlock extends JavaPlugin
{
	private static LogBlock logblock = null;
	private MySQLConnectionPool pool;
	private Consumer consumer = null;
	private CommandsHandler commandsHandler;
	private Updater updater = null;
	private Timer timer = null;
	private boolean errorAtLoading = false, noDb = false, connected = true;

	public static LogBlock getInstance() {
		return logblock;
	}

	public Consumer getConsumer() {
		return consumer;
	}

	public CommandsHandler getCommandsHandler() {
		return commandsHandler;
	}

	Updater getUpdater() {
		return updater;
	}

	@Override
	public void onLoad() {
		logblock = this;
		try {
			updater = new Updater(this);
			Config.load(this);
			if (checkVersion)
				getLogger().info("[LogBlock] Version check: " + updater.checkVersion());
			getLogger().info("[LogBlock] Connecting to " + user + "@" + url + "...");
			pool = new MySQLConnectionPool(url, user, password);
			final Connection conn = getConnection();
			if (conn == null) {
				noDb = true;
				return;
			}
			conn.close();
			if (updater.update())
				load(this);
			updater.checkTables();
		} catch (final NullPointerException ex) {
			getLogger().log(Level.SEVERE, "[LogBlock] Error while loading: ", ex);
		} catch (final Exception ex) {
			getLogger().severe("[LogBlock] Error while loading: " + ex.getMessage());
			errorAtLoading = true;
			return;
		}
		consumer = new Consumer(this);
	}

	@Override
	public void onEnable() {
		final PluginManager pm = getPluginManager();
		if (errorAtLoading) {
			pm.disablePlugin(this);
			return;
		}
		if (noDb)
			return;
		if (pm.getPlugin("WorldEdit") == null && !new File("lib/WorldEdit.jar").exists() && !new File("WorldEdit.jar").exists())
			try {
				download(getLogger(), new URL("http://diddiz.insane-architects.net/download/WorldEdit.jar"), new File("lib/WorldEdit.jar"));
				getLogger().info("[LogBlock] You've to restart/reload your server now.");
				pm.disablePlugin(this);
				return;
			} catch (final Exception ex) {
				getLogger().warning("[LogBlock] Failed to download WorldEdit. You may have to download it manually. You don't have to install it, just place the jar in the lib folder.");
			}
		commandsHandler = new CommandsHandler(this);
		getCommand("lb").setExecutor(commandsHandler);
		getLogger().info("[LogBlock] Permissions plugin not found. Using Bukkit Permissions.");
		if (enableAutoClearLog && autoClearLogDelay > 0)
			getServer().getScheduler().scheduleAsyncRepeatingTask(this, new AutoClearLog(this), 6000, autoClearLogDelay * 60 * 20);
		getServer().getScheduler().scheduleAsyncDelayedTask(this, new DumpedLogImporter(this));
		registerEvents();
		if (useBukkitScheduler) {
			if (getServer().getScheduler().scheduleAsyncRepeatingTask(this, consumer, delayBetweenRuns * 20, delayBetweenRuns * 20) > 0)
				getLogger().info("[LogBlock] Scheduled consumer with bukkit scheduler.");
			else {
				getLogger().warning("[LogBlock] Failed to schedule consumer with bukkit scheduler. Now trying schedule with timer.");
				timer = new Timer();
				timer.scheduleAtFixedRate(consumer, delayBetweenRuns * 1000, delayBetweenRuns * 1000);
			}
		} else {
			timer = new Timer();
			timer.scheduleAtFixedRate(consumer, delayBetweenRuns * 1000, delayBetweenRuns * 1000);
			getLogger().info("[LogBlock] Scheduled consumer with timer.");
		}
		for (final Tool tool : toolsByType.values())
			if (pm.getPermission("logblock.tools." + tool.name) == null) {
				final Permission perm = new Permission("logblock.tools." + tool.name, tool.permissionDefault);
				pm.addPermission(perm);
			}
		// perm.addParent("logblock.*", true);
		getLogger().info("LogBlock v" + getDescription().getVersion() + " by DiddiZ enabled.");
	}

	public void reload() {
		// TODO
	}

	private void registerEvents() {
		final PluginManager pm = getPluginManager();
		pm.registerEvents(new ToolListener(this), this);
		if (askRollbackAfterBan)
			pm.registerEvents(new BanListener(this), this);
		if (isLogging(Logging.BLOCKPLACE))
			pm.registerEvents(new BlockPlaceLogging(this), this);
		if (isLogging(Logging.BLOCKPLACE) || isLogging(Logging.LAVAFLOW) || isLogging(Logging.WATERFLOW))
			pm.registerEvents(new FluidFlowLogging(this), this);
		if (isLogging(Logging.BLOCKBREAK))
			pm.registerEvents(new BlockBreakLogging(this), this);
		if (isLogging(Logging.SIGNTEXT))
			pm.registerEvents(new SignChangeLogging(this), this);
		if (isLogging(Logging.FIRE))
			pm.registerEvents(new BlockBurnLogging(this), this);
		if (isLogging(Logging.SNOWFORM))
			pm.registerEvents(new SnowFormLogging(this), this);
		if (isLogging(Logging.SNOWFADE))
			pm.registerEvents(new SnowFadeLogging(this), this);
		if (isLogging(Logging.CREEPEREXPLOSION) || isLogging(Logging.TNTEXPLOSION) || isLogging(Logging.GHASTFIREBALLEXPLOSION) || isLogging(Logging.ENDERDRAGON) || isLogging(Logging.MISCEXPLOSION))
			pm.registerEvents(new ExplosionLogging(this), this);
		if (isLogging(Logging.LEAVESDECAY))
			pm.registerEvents(new LeavesDecayLogging(this), this);
		if (isLogging(Logging.CHESTACCESS)) {
			pm.registerEvents(new ChestAccessLogging(this), this);
		}
		if (isLogging(Logging.SWITCHINTERACT) || isLogging(Logging.DOORINTERACT) || isLogging(Logging.CAKEEAT) || isLogging(Logging.DIODEINTERACT) || isLogging(Logging.NOTEBLOCKINTERACT))
			pm.registerEvents(new InteractLogging(this), this);
		if (isLogging(Logging.KILL))
			pm.registerEvents(new KillLogging(this), this);
		if (isLogging(Logging.CHAT))
			pm.registerEvents(new ChatLogging(this), this);
		if (isLogging(Logging.ENDERMEN))
			pm.registerEvents(new EndermenLogging(this), this);
		if (isLogging(Logging.NATURALSTRUCTUREGROW) || isLogging(Logging.BONEMEALSTRUCTUREGROW))
			pm.registerEvents(new StructureGrowLogging(this), this);
		if (logPlayerInfo)
			pm.registerEvents(new PlayerInfoLogging(this), this);
	}

	@Override
	public void onDisable() {
		if (timer != null)
			timer.cancel();
		getServer().getScheduler().cancelTasks(this);
		if (consumer != null) {
			if (logPlayerInfo && getServer().getOnlinePlayers() != null)
				for (final Player player : getServer().getOnlinePlayers())
					consumer.queueLeave(player);
			if (consumer.getQueueSize() > 0) {
				getLogger().info("[LogBlock] Waiting for consumer ...");
				int tries = 10;
				while (consumer.getQueueSize() > 0) {
					getLogger().info("[LogBlock] Remaining queue size: " + consumer.getQueueSize());
					if (tries > 0)
						getLogger().info("[LogBlock] Remaining tries: " + tries);
					else {
						getLogger().info("Unable to save queue to database. Trying to write to a local file.");
						try {
							consumer.writeToFile();
							getLogger().info("Successfully dumped queue.");
						} catch (final FileNotFoundException ex) {
							getLogger().info("Failed to write. Given up.");
							break;
						}
					}
					consumer.run();
					tries--;
				}
			}
		}
		if (pool != null)
			pool.close();
		getLogger().info("LogBlock disabled.");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		if (noDb)
			sender.sendMessage(ChatColor.RED + "No database connected. Check your MySQL user/pw and database for typos. Start/restart your MySQL server.");
		return true;
	}

	public boolean hasPermission(CommandSender sender, String permission) {
		return sender.hasPermission(permission);
	}

	public Connection getConnection() {
		try {
			final Connection conn = pool.getConnection();
			if (!connected) {
				getLogger().info("[LogBlock] MySQL connection rebuild");
				connected = true;
			}
			return conn;
		} catch (final Exception ex) {
			if (connected) {
				getLogger().log(Level.SEVERE, "[LogBlock] Error while fetching connection: ", ex);
				connected = false;
			} else
				getLogger().severe("[LogBlock] MySQL connection lost");
			return null;
		}
	}

	/**
	 * @param params
	 * QueryParams that contains the needed columns (all other will be filled with default values) and the params. World is required.
	 */
	public List<BlockChange> getBlockChanges(QueryParams params) throws SQLException {
		final Connection conn = getConnection();
		Statement state = null;
		if (conn == null)
			throw new SQLException("No connection");
		try {
			state = conn.createStatement();
			final ResultSet rs = state.executeQuery(params.getQuery());
			final List<BlockChange> blockchanges = new ArrayList<BlockChange>();
			while (rs.next())
				blockchanges.add(new BlockChange(rs, params));
			return blockchanges;
		} finally {
			if (state != null)
				state.close();
			conn.close();
		}
	}

	public int getCount(QueryParams params) throws SQLException {
		final Connection conn = getConnection();
		Statement state = null;
		if (conn == null)
			throw new SQLException("No connection");
		try {
			state = conn.createStatement();
			final QueryParams p = params.clone();
			p.needCount = true;
			final ResultSet rs = state.executeQuery(p.getQuery());
			if (!rs.next())
				return 0;
			return rs.getInt(1);
		} finally {
			if (state != null)
				state.close();
			conn.close();
		}
	}
}
