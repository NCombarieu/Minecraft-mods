package com.ncombarieu.chronomancie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ncombarieu.chronomancie.commande.ChronoCommande;
import com.ncombarieu.chronomancie.faille.Failles;
import com.ncombarieu.chronomancie.fx.Ephemere;
import com.ncombarieu.chronomancie.item.UtilisationArtefacts;
import com.ncombarieu.chronomancie.monde.CicatricesExplosions;
import com.ncombarieu.chronomancie.pouvoir.Echo;
import com.ncombarieu.chronomancie.pouvoir.Restauration;
import com.ncombarieu.chronomancie.pouvoir.Retour;
import com.ncombarieu.chronomancie.pouvoir.Stase;
import com.ncombarieu.chronomancie.temps.Memoire;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.level.ServerPlayer;

public class Chronomancie implements ModInitializer {
	public static final String MOD_ID = "chronomancie";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ChronoConfig.charger();
		UtilisationArtefacts.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> ChronoCommande.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Memoire.enregistrer(server);
			Retour.tick();
			Echo.tick();
			Stase.tick();
			Restauration.tick(server.getTickCount());
			Failles.tick(server);
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer joueur && Retour.enCours(joueur)) {
				return false;
			}
			if (Echo.estUnEcho(entity)) {
				Echo.encaisser(entity);
				return false;
			}
			return Stase.autoriserDegats(entity, source, amount);
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			Ephemere.verifier(entity);
			Stase.verifier(entity);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Failles.accueillir(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Memoire.oublier(handler.getPlayer().getUUID()));

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// On remet le monde en ordre avant la sauvegarde finale
			Stase.toutLiberer();
			Retour.toutArreter();
			Echo.toutArreter();
			Restauration.toutArreter();
			Failles.toutFermer();
			Ephemere.toutOublier();
			Memoire.toutOublier();
			CicatricesExplosions.toutOublier();
		});

		LOGGER.info("Chronomancie : le temps s'est mis en marche.");
	}
}
