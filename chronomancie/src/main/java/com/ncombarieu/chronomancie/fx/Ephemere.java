package com.ncombarieu.chronomancie.fx;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Entités temporaires du mod (affichages, échos, vestiges…). Elles sont marquées d'un tag :
 * si le serveur s'arrête brutalement, celles qui reviennent au chargement du monde sont supprimées.
 */
public final class Ephemere {
	public static final String TAG = "chronomancie.ephemere";

	private static final Set<UUID> VIVANTES = new HashSet<>();

	private Ephemere() {
	}

	public static <T extends Entity> T ajouter(final ServerLevel level, final T entity) {
		entity.addTag(TAG);
		VIVANTES.add(entity.getUUID());
		level.addFreshEntity(entity);
		return entity;
	}

	public static void retirer(final Entity entity) {
		if (entity != null) {
			VIVANTES.remove(entity.getUUID());
			entity.discard();
		}
	}

	/** Appelé à chaque chargement d'entité : on élimine les restes d'une session précédente. */
	public static void verifier(final Entity entity) {
		if (entity.entityTags().contains(TAG) && !VIVANTES.contains(entity.getUUID())) {
			entity.discard();
		}
	}

	public static void toutOublier() {
		VIVANTES.clear();
	}
}
