package com.ncombarieu.chronomancie.commande;

import java.util.Collection;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.faille.Epoque;
import com.ncombarieu.chronomancie.faille.Faille;
import com.ncombarieu.chronomancie.faille.Failles;
import com.ncombarieu.chronomancie.item.Artefact;
import com.ncombarieu.chronomancie.item.Artefacts;
import com.ncombarieu.chronomancie.item.Traite;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * /chrono guide                       — redonne le traité (tout le monde)
 * /chrono donner <artefact> [joueurs] [quantité]   — ops
 * /chrono faille [époque] [position]  — ouvre une faille (ops)
 * /chrono failles                     — liste les failles ouvertes (ops)
 * /chrono fermer                      — referme toutes les failles (ops)
 * /chrono recharger                   — relit config/chronomancie.json (ops)
 */
public final class ChronoCommande {
	private static final SimpleCommandExceptionType INCONNU = new SimpleCommandExceptionType(Component.literal("Inconnu au bataillon du temps."));
	private static final SimpleCommandExceptionType PAS_DE_LIEU = new SimpleCommandExceptionType(Component.literal("Aucun sol ferme trouvé ici pour ouvrir une faille."));

	private ChronoCommande() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("chrono")
				.then(Commands.literal("guide").executes(ChronoCommande::guide))
				.then(Commands.literal("donner")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("artefact", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of(Artefact.values()).stream().map(a -> a.id), b))
								.executes(c -> donner(c, List.of(c.getSource().getPlayerOrException()), 1))
								.then(Commands.argument("joueurs", EntityArgument.players())
										.executes(c -> donner(c, EntityArgument.getPlayers(c, "joueurs"), 1))
										.then(Commands.argument("quantite", IntegerArgumentType.integer(1, 64))
												.executes(c -> donner(c, EntityArgument.getPlayers(c, "joueurs"), IntegerArgumentType.getInteger(c, "quantite")))))))
				.then(Commands.literal("faille")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> faille(c, null, null))
						.then(Commands.argument("epoque", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of(Epoque.values()).stream().map(e -> e.id), b))
								.executes(c -> faille(c, StringArgumentType.getString(c, "epoque"), null))
								.then(Commands.argument("position", Vec3Argument.vec3())
										.executes(c -> faille(c, StringArgumentType.getString(c, "epoque"), Vec3Argument.getVec3(c, "position"))))))
				.then(Commands.literal("failles")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ChronoCommande::lister))
				.then(Commands.literal("fermer")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ChronoCommande::fermer))
				.then(Commands.literal("recharger")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ChronoCommande::recharger)));
	}

	private static int guide(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer joueur = c.getSource().getPlayerOrException();
		Artefacts.offrir(joueur, Traite.creer());
		c.getSource().sendSuccess(() -> Component.literal("Le Traité de Chronomancie est dans ton inventaire.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int donner(final CommandContext<CommandSourceStack> c, final Collection<ServerPlayer> joueurs, final int quantite) throws CommandSyntaxException {
		Artefact artefact = Artefact.parId(StringArgumentType.getString(c, "artefact"));
		if (artefact == null) {
			throw INCONNU.create();
		}
		for (ServerPlayer joueur : joueurs) {
			int reste = quantite;
			while (reste > 0) {
				int lot = artefact.estOutil() ? 1 : Math.min(reste, 64);
				Artefacts.offrir(joueur, Artefacts.creer(artefact, lot));
				reste -= lot;
			}
		}
		c.getSource().sendSuccess(() -> Component.literal(quantite + " × " + artefact.nom + " donné à " + joueurs.size() + " joueur(s).").withStyle(artefact.couleur), true);
		return joueurs.size();
	}

	private static int faille(final CommandContext<CommandSourceStack> c, final String idEpoque, final Vec3 position) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		Epoque epoque = idEpoque == null ? Epoque.values()[source.getLevel().getRandom().nextInt(Epoque.values().length)] : Epoque.parId(idEpoque);
		if (epoque == null) {
			throw INCONNU.create();
		}
		Vec3 lieu = position;
		if (lieu == null) {
			lieu = Failles.trouverLieu(source.getLevel(), source.getPosition(), 8, 14, source.getLevel().getRandom());
			if (lieu == null) {
				throw PAS_DE_LIEU.create();
			}
		}
		Failles.ouvrir(source.getLevel(), lieu, epoque);
		Vec3 ici = lieu;
		source.sendSuccess(() -> Component.literal(String.format("Faille %s ouverte en %.0f %.0f %.0f.", epoque.complement, ici.x, ici.y, ici.z)).withStyle(epoque.couleur), true);
		return 1;
	}

	private static int lister(final CommandContext<CommandSourceStack> c) {
		List<Faille> failles = Failles.actives();
		if (failles.isEmpty()) {
			c.getSource().sendSuccess(() -> Component.literal("Aucune faille ouverte. Le temps est calme.").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}
		for (Faille f : failles) {
			Vec3 p = f.centre();
			c.getSource().sendSuccess(() -> Component.literal(String.format("• %s — %s — %.0f %.0f %.0f (%s)", f.epoque().nom, f.etat().name().toLowerCase(),
					p.x, p.y, p.z, f.level().dimension().identifier())).withStyle(f.epoque().couleur), false);
		}
		return failles.size();
	}

	private static int fermer(final CommandContext<CommandSourceStack> c) {
		int n = Failles.actives().size();
		Failles.toutFermer();
		c.getSource().sendSuccess(() -> Component.literal(n + " faille(s) refermée(s).").withStyle(ChatFormatting.GRAY), true);
		return n;
	}

	private static int recharger(final CommandContext<CommandSourceStack> c) {
		ChronoConfig.charger();
		c.getSource().sendSuccess(() -> Component.literal("Réglages de Chronomancie relus.").withStyle(ChatFormatting.GRAY), true);
		return 1;
	}
}
