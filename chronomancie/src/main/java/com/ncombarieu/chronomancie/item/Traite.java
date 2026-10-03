package com.ncombarieu.chronomancie.item;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/** Le « Traité de Chronomancie » : le livre-guide offert à chaque nouveau joueur. */
public final class Traite {
	private Traite() {
	}

	public static ItemStack creer() {
		List<Filterable<Component>> pages = new ArrayList<>();
		pages.add(page(
				titre("Traité de\nChronomancie"),
				texte("\n\nLe temps de ce monde est fêlé.\n\nÇà et là, des "),
				accent("failles", ChatFormatting.DARK_PURPLE),
				texte(" s'ouvrent sur d'autres époques. Suis leur colonne de lumière…\n\n"),
				petit("— un chronomancien anonyme")));
		pages.add(page(
				titre("I. Les failles"),
				texte("\n\nApproche-toi à moins de 6 blocs : la faille s'éveille et recrache des "),
				accent("vestiges", ChatFormatting.DARK_RED),
				texte(" de son époque.\n\nSurvis à toutes les vagues : elle s'effondre et libère des "),
				accent("fragments temporels", ChatFormatting.LIGHT_PURPLE),
				texte(".")));
		pages.add(page(
				titre("II. Les époques"),
				texte("\n\n"),
				accent("Ère Glaciaire\n", ChatFormatting.DARK_AQUA),
				accent("Âge des Pharaons\n", ChatFormatting.GOLD),
				accent("Ère des Illusions\n", ChatFormatting.DARK_GREEN),
				accent("Futur Lointain\n", ChatFormatting.DARK_PURPLE),
				texte("\nChacune a ses créatures et ses trésors. Les refermer toutes fait de toi un maître du temps.")));
		pages.add(page(
				titre("III. Sablier du Retour"),
				texte("\n\n"),
				petit(" G = or   F = fragment\n"),
				code("  F \n G⌚G\n  F \n"),
				texte("\nClic droit : tu remontes ton propre chemin des 8 dernières secondes et retrouves la santé d'alors.")));
		pages.add(page(
				titre("IV. Miroir d'Écho"),
				texte("\n\n"),
				petit(" V = vitre   P = perle\n"),
				code(" VFV\n FPF\n VFV\n"),
				texte("\nTon écho surgit là où tu étais et rejoue tes gestes. Les monstres le prennent pour toi.")));
		pages.add(page(
				titre("V. Montre de Stase"),
				texte("\n\n"),
				petit(" G = glace compacte\n"),
				code(" FGF\n G⌚G\n FGF\n"),
				texte("\nLe temps s'arrête autour de toi. Frappe les ennemis figés : tous les coups tombent d'un seul bloc au dégel.")));
		pages.add(page(
				titre("VI. Chronoscope"),
				texte("\n\n"),
				petit(" L = longue-vue\n"),
				code("  F \n FLF\n  F \n"),
				texte("\nLe monde se souvient des explosions. Le Chronoscope remet chaque bloc soufflé à sa place. Accroupi : simple aperçu.")));
		pages.add(page(
				titre("VII. Recharger"),
				texte("\n\nChaque artefact a des charges. Tiens un "),
				accent("fragment", ChatFormatting.LIGHT_PURPLE),
				texte(" dans l'autre main, accroupis-toi et utilise l'artefact : il regagne un quart de ses charges.\n\n"),
				petit("/chrono guide redonne ce traité.")));

		ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
		livre.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Traité de Chronomancie"), "Chronomancien", 0, pages, true));
		livre.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return livre;
	}

	private static Filterable<Component> page(final Component... morceaux) {
		MutableComponent page = Component.empty();
		for (Component morceau : morceaux) {
			page.append(morceau);
		}
		return Filterable.passThrough(page);
	}

	private static Component titre(final String texte) {
		return Component.literal(texte).withStyle(ChatFormatting.DARK_BLUE, ChatFormatting.BOLD);
	}

	private static Component texte(final String texte) {
		return Component.literal(texte).withStyle(ChatFormatting.BLACK);
	}

	private static Component accent(final String texte, final ChatFormatting couleur) {
		return Component.literal(texte).withStyle(couleur, ChatFormatting.BOLD);
	}

	private static Component petit(final String texte) {
		return Component.literal(texte).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
	}

	private static Component code(final String texte) {
		return Component.literal(texte).withStyle(ChatFormatting.DARK_GRAY);
	}
}
