package com.ncombarieu.hameau;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class Hameau implements ModInitializer {
	public static final String MOD_ID = "hameau";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		HameauConfig.charger();
		Cerveau.demarrer();
		Evenements.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> HameauCommande.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Ames.charger(server);
			Vie.maintenirCharges(server, java.util.List.of());
			if (HameauConfig.get().horsLigne()) {
				LOGGER.info("Hameau : vie hors ligne activée. Pensez à pause-when-empty-seconds=-1 dans server.properties.");
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entite, level) -> {
			Bulles.verifier(entite);
			Corps.verifier(entite);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Vie.tick(server);
			Actions.tick(server);
			Corps.tick(server);
			Bulles.tick(server.getTickCount());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Ames.sauvegarder();
			Actions.toutOublier();
			Bulles.toutEffacer();
			Corps.toutRetirer();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> handler.getPlayer().sendSystemMessage(
				Component.literal("Les villageois d'ici ont leur caractère. Parle-leur dans le chat quand tu es près d'eux (cite leur prénom pour t'adresser à l'un d'eux) ; accroupi + clic droit pour leur offrir un objet.")
						.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));

		LOGGER.info("Hameau : le village s'éveille ({}).", Cerveau.pret() ? HameauConfig.get().modele : "sans clé API");
	}
}
