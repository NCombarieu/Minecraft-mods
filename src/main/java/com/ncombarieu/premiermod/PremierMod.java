package com.ncombarieu.premiermod;

import com.ncombarieu.premiermod.commands.ModCommands;
import com.ncombarieu.premiermod.events.ModEvents;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PremierMod implements ModInitializer {
	public static final String MOD_ID = "premiermod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Point d'entrée du mod : on branche nos commandes et nos événements
		ModCommands.register();
		ModEvents.register();

		LOGGER.info("Premier Mod chargé !");
	}
}
