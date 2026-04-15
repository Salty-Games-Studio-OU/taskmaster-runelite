/*
 * Copyright (c) 2021, andmcadams
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.taskmaster;


import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import okhttp3.*;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
	name = "Taskmaster"
)
public class TaskmasterPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;


	@Inject
	private WikiSyncConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private SyncButtonManager syncButtonManager;

	private static final int SECONDS_BETWEEN_UPLOADS = 10;
	private static final int SECONDS_BETWEEN_MANIFEST_CHECKS = 1200;

	private static final String MANIFEST_URL = "https://sync.runescape.wiki/runelite/manifest";
	private static final String SUBMIT_URL = "https://taskapi.salty.gg/api/runelite/submit";
	private static final String HEALTH_URL = "https://taskapi.salty.gg/api/runelite/health";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private static final int[] LEAGUE_TASK_VARPS = {
		2616, 2617, 2618, 2619, 2620, 2621, 2622, 2623, 2624, 2625, 2626, 2627,
		2628, 2629, 2630, 2631, 2808, 2809, 2810, 2811, 2812, 2813, 2814, 2815,
		2816, 2817, 2818, 2819, 2820, 2821, 2822, 2823, 2824, 2825, 2826, 2827,
		2828, 2829, 2830, 2831, 2832, 2833, 2834, 2835, 3339, 3340, 3341, 3342,
		4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043, 4044, 4045, 4046, 4047,
		4048, 4049,
	};

	public static final String CONFIG_GROUP_KEY = "WikiSync";
	// THIS VERSION SHOULD BE INCREMENTED EVERY RELEASE WHERE WE ADD A NEW TOGGLE
	public static final int VERSION = 1;

	private Manifest manifest;
	private Map<PlayerProfile, PlayerData> playerDataMap = new HashMap<>();
	private boolean webSocketStarted;
	private int cyclesSinceSuccessfulCall = 0;

	// Keeps track of what collection log slots the user has set.
	private static final BitSet clogItemsBitSet = new BitSet();
	private static Integer clogItemsCount = null;
	// Map item ids to bit index in the bitset
	private static final HashMap<Integer, Integer> collectionLogItemIdToBitsetIndex = new HashMap<>();
	private int tickCollectionLogScriptFired = -1;
	private final HashSet<Integer> collectionLogItemIdsFromCache = new HashSet<>();

	@Provides
	WikiSyncConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiSyncConfig.class);
	}

	@Override
	public void startUp()
	{
		clientThread.invoke(() -> {
			if (client.getGameState().ordinal() < GameState.LOGIN_SCREEN.ordinal())
			{
				log.debug("Too early to start up... state={}", client.getGameState());
				return false;
			}
			collectionLogItemIdsFromCache.addAll(parseCacheForClog());
			populateCollectionLogItemIdToBitsetIndex();
			return true;
		});

		checkManifest();
		checkHealth();
		syncButtonManager.startUp();
	}


	@Override
	protected void shutDown()
	{
		log.debug("WikiSync stopped!");
		clogItemsBitSet.clear();
		clogItemsCount = null;
		syncButtonManager.shutDown();
	}


	/**
	 * Finds the index this itemId is assigned to in the collections mapping.
	 * @param itemId: The itemId to look up
	 * @return The index of the bit that represents the given itemId, if it is in the map. -1 otherwise.
	 */
	private int lookupCollectionLogItemIndex(int itemId) {
		// The map has not loaded yet, or failed to load.
		if (collectionLogItemIdToBitsetIndex.isEmpty()) {
			return -1;
		}
		Integer result = collectionLogItemIdToBitsetIndex.get(itemId);
		if (result == null) {
			log.debug("Item id {} not found in the mapping of items", itemId);
			return -1;
		}
		return result;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				checkHealth();
				submitTask();
				break;
			// When hopping, we need to clear any state related to the player
			case HOPPING:
			case LOGGING_IN:
			case CONNECTION_LOST:
				clogItemsBitSet.clear();
				clogItemsCount = null;
				break;
		}
	}

	@Schedule(
		period = 1800,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void healthTask()
	{
		checkHealth();
	}

	private void checkHealth()
	{
		Request request = new Request.Builder()
			.url(HEALTH_URL)
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Health check failed: ", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					log.debug("Health check response: {}", response.code());
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired preFired) {
		if (syncButtonManager.isSyncAllowed() && preFired.getScriptId() == 4100) {
			tickCollectionLogScriptFired = client.getTickCount();
			if (collectionLogItemIdToBitsetIndex.isEmpty())
			{
				return;
			}
			clogItemsCount = collectionLogItemIdsFromCache.size();
			Object[] args = preFired.getScriptEvent().getArguments();
			int itemId = (int) args[1];
			int idx = lookupCollectionLogItemIndex(itemId);
			// We should never return -1 under normal circumstances
			if (idx != -1)
				clogItemsBitSet.set(idx);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		// Submit the collection log data two ticks after the first script prefires
		if (tickCollectionLogScriptFired != -1 &&
				tickCollectionLogScriptFired + 2 > client.getTickCount()) {
			tickCollectionLogScriptFired = -1;
			if (manifest == null) {
				client.addChatMessage(ChatMessageType.CONSOLE, "WikiSync", "Failed to sync collection log. Try restarting the WikiSync plugin.", "WikiSync");
				return;
			}
			submitTask();
		}
	}


	@Schedule(
		period = SECONDS_BETWEEN_UPLOADS,
		unit = ChronoUnit.SECONDS
	)
	public void queueSubmitTask() {
		submitTask();
	}

	synchronized public void submitTask()
	{
		// TODO: do we want other GameStates?
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (client.getLocalPlayer() == null)
		{
			return;
		}

		String username = client.getLocalPlayer().getName();
		RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
		PlayerProfile profileKey = new PlayerProfile(username, profileType);

		PlayerData newPlayerData = getPlayerData();
		PlayerData oldPlayerData = playerDataMap.computeIfAbsent(profileKey, k -> new PlayerData());

		// Subtraction is done in place so newPlayerData becomes a map of only changed fields
		subtract(newPlayerData, oldPlayerData);
		if (newPlayerData.isEmpty())
		{
			return;
		}
		submitPlayerData(profileKey, newPlayerData, oldPlayerData);
	}

	@Schedule(
			period = SECONDS_BETWEEN_MANIFEST_CHECKS,
			unit = ChronoUnit.SECONDS,
			asynchronous = true
	)
	public void manifestTask()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			checkManifest();
		}
	}


	private PlayerData getPlayerData()
	{
		PlayerData out = new PlayerData();

		// Always collect league task varps
		for (int varpId : LEAGUE_TASK_VARPS)
		{
			try {
				out.varp.put(varpId, client.getVarpValue(varpId));
			} catch (ArrayIndexOutOfBoundsException e) {
				log.debug("Unable to access league varp {}: {}", varpId, e.toString());
			}
		}

		if (manifest != null)
		{
			for (int varbitId : manifest.varbits)
			{
				try {
					out.varb.put(varbitId, client.getVarbitValue(varbitId));
				} catch (ArrayIndexOutOfBoundsException e) {
					log.debug("Unable to access varbit {}: {}", varbitId, e.toString());
				}
			}
			for (int varpId : manifest.varps)
			{
				try {
					out.varp.put(varpId, client.getVarpValue(varpId));
				} catch (ArrayIndexOutOfBoundsException e) {
					log.debug("Unable to access varplayer {}: {}", varpId, e.toString());
				}
			}
			for (Skill s : Skill.values())
			{
				out.level.put(s.getName(), client.getRealSkillLevel(s));
			}
			out.collectionLogSlots = Base64.getEncoder().encodeToString(clogItemsBitSet.toByteArray());
			out.collectionLogItemCount = clogItemsCount;
		}

		if (client.getLocalPlayer() != null)
		{
			WorldPoint wp = client.getLocalPlayer().getWorldLocation();
			out.location = new PlayerLocation(wp.getX(), wp.getY(), wp.getPlane());
		}
		return out;
	}

	private void subtract(PlayerData newPlayerData, PlayerData oldPlayerData)
	{
		oldPlayerData.varb.forEach(newPlayerData.varb::remove);
		oldPlayerData.varp.forEach(newPlayerData.varp::remove);
		oldPlayerData.level.forEach(newPlayerData.level::remove);
		if (newPlayerData.collectionLogSlots.equals(oldPlayerData.collectionLogSlots))
			newPlayerData.clearCollectionLog();
	}

	private void merge(PlayerData oldPlayerData, PlayerData delta)
	{
		oldPlayerData.varb.putAll(delta.varb);
		oldPlayerData.varp.putAll(delta.varp);
		oldPlayerData.level.putAll(delta.level);
		oldPlayerData.collectionLogSlots = delta.collectionLogSlots;
		oldPlayerData.collectionLogItemCount = delta.collectionLogItemCount;
	}

	private void submitPlayerData(PlayerProfile profileKey, PlayerData delta, PlayerData old)
	{
		// If cyclesSinceSuccessfulCall is not a perfect square, we should not try to submit.
		// This gives us quadratic backoff.
		cyclesSinceSuccessfulCall += 1;
		if (Math.pow((int) Math.sqrt(cyclesSinceSuccessfulCall), 2) != cyclesSinceSuccessfulCall)
		{
			return;
		}

		PlayerDataSubmission submission = new PlayerDataSubmission(
				profileKey.getUsername(),
				profileKey.getProfileType().name(),
				delta
		);

		Request request = new Request.Builder()
				.url(SUBMIT_URL)
				.post(RequestBody.create(JSON, gson.toJson(submission)))
				.build();

		log.debug("Submitting data for {}", submission.getUsername());
		Call call = okHttpClient.newCall(request);
		call.timeout().timeout(3, TimeUnit.SECONDS);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to submit: ", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful()) {
						log.debug("Failed to submit: {}", response.code());
						return;
					}
					merge(old, delta);
					cyclesSinceSuccessfulCall = 0;
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private void checkManifest()
	{
		Request request = new Request.Builder()
				.url(MANIFEST_URL)
				.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to get manifest: ", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful())
					{
						log.debug("Failed to get manifest: {}", response.code());
						return;
					}
					InputStream in = response.body().byteStream();
					manifest = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Manifest.class);
					populateCollectionLogItemIdToBitsetIndex();
				}
				catch (JsonParseException e)
				{
					log.debug("Failed to parse manifest: ", e);
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	@Schedule(
		period = 30,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)

	private void populateCollectionLogItemIdToBitsetIndex()
	{
		if (manifest == null)
		{
			log.debug("Manifest is not present so the collection log bitset index will not be updated");
			return;
		}
		clientThread.invoke(() -> {
			// Add missing keys in order to the map. Order is extremely important here so
			// we get a stable map given the same cache data.
			List<Integer> itemIdsMissingFromManifest = collectionLogItemIdsFromCache
					.stream()
					.filter((t) -> !manifest.collections.contains(t))
					.sorted()
					.collect(Collectors.toList());

			int currentIndex = 0;
			collectionLogItemIdToBitsetIndex.clear();
			for (Integer itemId : manifest.collections)
				collectionLogItemIdToBitsetIndex.put(itemId, currentIndex++);
			for (Integer missingItemId : itemIdsMissingFromManifest) {
				collectionLogItemIdToBitsetIndex.put(missingItemId, currentIndex++);
			}
		});
	}

	/**
	 * Parse the enums and structs in the cache to figure out which item ids
	 * exist in the collection log. This can be diffed with the manifest to
	 * determine the item ids that need to be appended to the end of the
	 * bitset we send to the WikiSync server.
	 */
	private HashSet<Integer> parseCacheForClog()
	{
		HashSet<Integer> itemIds = new HashSet<>();
		// 2102 - Struct that contains the highest level tabs in the collection log (Bosses, Raids, etc)
		// https://chisel.weirdgloop.org/structs/index.html?type=enums&id=2102
		int[] topLevelTabStructIds = client.getEnum(2102).getIntVals();
		for (int topLevelTabStructIndex : topLevelTabStructIds)
		{
			// The collection log top level tab structs contain a param that points to the enum
			// that contains the pointers to sub tabs.
			// ex: https://chisel.weirdgloop.org/structs/index.html?type=structs&id=471
			StructComposition topLevelTabStruct = client.getStructComposition(topLevelTabStructIndex);

			// Param 683 contains the pointer to the enum that contains the subtabs ids
			// ex: https://chisel.weirdgloop.org/structs/index.html?type=enums&id=2103
			int[] subtabStructIndices = client.getEnum(topLevelTabStruct.getIntValue(683)).getIntVals();
			for (int subtabStructIndex : subtabStructIndices) {

				// The subtab structs are for subtabs in the collection log (Commander Zilyana, Chambers of Xeric, etc.)
				// and contain a pointer to the enum that contains all the item ids for that tab.
				// ex subtab struct: https://chisel.weirdgloop.org/structs/index.html?type=structs&id=476
				// ex subtab enum: https://chisel.weirdgloop.org/structs/index.html?type=enums&id=2109
				StructComposition subtabStruct = client.getStructComposition(subtabStructIndex);
				int[] clogItems = client.getEnum(subtabStruct.getIntValue(690)).getIntVals();
				for (int clogItemId : clogItems) itemIds.add(clogItemId);
			}
		}

		// Some items with data saved on them have replacements to fix a duping issue (satchels, flamtaer bag)
		// Enum 3721 contains a mapping of the item ids to replace -> ids to replace them with
		EnumComposition replacements = client.getEnum(3721);
		for (int badItemId : replacements.getKeys())
			itemIds.remove(badItemId);
		for (int goodItemId : replacements.getIntVals())
			itemIds.add(goodItemId);

		return itemIds;
	}

}
