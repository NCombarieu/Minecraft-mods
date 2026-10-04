package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Le menu d'ordres d'un villageois au service d'un joueur : un coffre dont chaque objet est un ordre. Il s'ouvre accroupi, main vide,
 * par un clic droit sur le villageois (ou par /hameau ordres). Tout se passe côté serveur : aucun mod n'est requis chez le joueur.
 */
public final class MenuOrdres extends ChestMenu {
	/** Un ordre : l'objet qui le représente, son nom, son explication, et l'action lancée (comme /hameau faire). */
	private record Ordre(Item icone, String nom, String detail, String action) {
	}

	private static final Ordre[] ORDRES = new Ordre[27];

	static {
		ORDRES[0] = new Ordre(Items.COMPASS, "Suis-moi", "Il reste dans tes pas.", "suivre");
		ORDRES[1] = new Ordre(Items.CAMPFIRE, "Reste ici", "Il t'attend sur place.", "rester");
		ORDRES[2] = new Ordre(Items.BARRIER, "Arrête", "Il laisse tomber ce qu'il est en train de faire.", "arreter");
		ORDRES[3] = new Ordre(Items.BREAD, "Mange", "Il mange ce qu'il a en poche et se soigne.", "manger");
		ORDRES[4] = new Ordre(Items.HOPPER, "Ramasse", "Il ramasse ce qui traîne par terre autour de lui.", "ramasser");
		ORDRES[5] = new Ordre(Items.CHEST, "Range tout", "Il vide ses poches dans le coffre le plus proche.", "deposer");
		ORDRES[6] = new Ordre(Items.IRON_HOE, "Moissonne", "Il récolte les cultures mûres des environs et ressème.", "recolter");
		ORDRES[7] = new Ordre(Items.IRON_AXE, "Coupe un arbre", "Il abat l'arbre le plus proche et garde le bois.", "couper");
		ORDRES[8] = new Ordre(Items.IRON_SWORD, "Chasse", "Il tue la bête la plus proche et ramasse ce qu'elle laisse.", "frapper");
		ORDRES[9] = new Ordre(Items.COAL, "Mine du charbon", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner charbon 8");
		ORDRES[10] = new Ordre(Items.RAW_IRON, "Mine du fer", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner fer 8");
		ORDRES[11] = new Ordre(Items.RAW_COPPER, "Mine du cuivre", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner cuivre 8");
		ORDRES[12] = new Ordre(Items.RAW_GOLD, "Mine de l'or", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner or 6");
		ORDRES[13] = new Ordre(Items.REDSTONE, "Mine de la redstone", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner redstone 6");
		ORDRES[14] = new Ordre(Items.LAPIS_LAZULI, "Mine du lapis", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner lapis 6");
		ORDRES[15] = new Ordre(Items.DIAMOND, "Mine du diamant", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner diamant 4");
		ORDRES[16] = new Ordre(Items.EMERALD, "Mine de l'émeraude", "Il creuse sa galerie jusqu'au filon le plus proche.", "miner emeraude 4");
		ORDRES[17] = new Ordre(Items.COBBLESTONE, "Mine de la pierre", "Il rapporte de la pierre.", "miner pierre 16");
		ORDRES[22] = new Ordre(Items.BELL, "Viens ici", "Il te rejoint.", "aller");
		ORDRES[26] = new Ordre(Items.OAK_DOOR, "Congédier", "Il n'est plus à ton service.", "liberer");
	}

	private final UUID villageois;

	private MenuOrdres(final int id, final Inventory inventaire, final UUID villageois) {
		super(MenuType.GENERIC_9x3, id, inventaire, vitrine(), 3);
		this.villageois = villageois;
	}

	private static SimpleContainer vitrine() {
		SimpleContainer vitrine = new SimpleContainer(27);
		for (int i = 0; i < 27; i++) {
			if (ORDRES[i] != null) {
				ItemStack icone = new ItemStack(ORDRES[i].icone());
				icone.set(DataComponents.CUSTOM_NAME, Component.literal(ORDRES[i].nom()).withStyle(style -> style.withColor(ChatFormatting.YELLOW).withItalic(false)));
				icone.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(ORDRES[i].detail()).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)))));
				vitrine.setItem(i, icone);
			}
		}
		return vitrine;
	}

	static void ouvrir(final ServerPlayer joueur, final Villager villageois, final Ame ame) {
		joueur.openMenu(new SimpleMenuProvider((id, inventaire, j) -> new MenuOrdres(id, inventaire, villageois.getUUID()), Component.literal("Ordres pour " + ame.nom)));
	}

	/** Un clic donne l'ordre ; rien ne se prend ni ne se dépose dans ce coffre-là. */
	@Override
	public void clicked(final int case_, final int bouton, final ContainerInput geste, final Player joueur) {
		if (!(joueur instanceof ServerPlayer maitre)) {
			return;
		}
		Ordre ordre = case_ >= 0 && case_ < 27 ? ORDRES[case_] : null;
		// Le client a cru prendre l'objet : on lui renvoie l'état réel.
		sendAllDataToRemote();
		if (ordre == null) {
			return;
		}
		Villager present = Vie.trouver(maitre.level().getServer(), villageois);
		Ame ame = present == null ? null : Ames.de(present);
		maitre.closeContainer();
		if (ame == null || !present.isAlive() || !maitre.getName().getString().equals(ame.maitre)) {
			maitre.sendSystemMessage(Component.literal("Il n'est plus à ton service.").withStyle(ChatFormatting.RED));
			return;
		}
		long maintenant = maitre.level().getServer().getTickCount();
		String qui = maitre.getName().getString();
		if (ordre.action().equals("liberer")) {
			ame.noter(qui + " t'a rendu ta liberté.");
			Vie.affranchir(present, ame);
			return;
		}
		String[] mots = ordre.action().split(" ", 2);
		String cible = switch (mots[0]) {
			case "suivre", "aller" -> qui;
			case "couper" -> Actions.arbre(present);
			case "deposer" -> Actions.coffre(present);
			case "frapper" -> Actions.gibier(present);
			default -> mots.length > 1 ? mots[1] : null;
		};
		if (cible == null && List.of("couper", "deposer", "frapper").contains(mots[0])) {
			maitre.sendSystemMessage(Component.literal(ame.nom + " ne voit " + switch (mots[0]) {
				case "couper" -> "aucun arbre";
				case "deposer" -> "aucun coffre";
				default -> "aucune bête";
			} + " dans les parages.").withStyle(ChatFormatting.RED));
			return;
		}
		ame.noter(qui + " t'a ordonné : « " + ordre.nom() + " ». Tu t'exécutes.");
		Cerveau.Decision decision = new Cerveau.Decision(null, null, null, null, false, mots[0], cible, cible, new ArrayList<>(), null, null, null, Map.of(), null, null, null, null, 0, 0);
		Actions.lancer(present, ame, decision, maintenant);
		Bulles.montrer(present, "* " + ordre.nom().toLowerCase() + " *", true, maintenant);
		maitre.sendSystemMessage(Component.literal(ame.nom + " : " + ordre.nom().toLowerCase() + ".").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
	}

	@Override
	public ItemStack quickMoveStack(final Player joueur, final int case_) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(final Player joueur) {
		return true;
	}
}
