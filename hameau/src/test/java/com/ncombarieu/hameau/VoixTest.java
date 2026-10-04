package com.ncombarieu.hameau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** La lecture des sons reçus des services de voix : tout doit ressortir en 48 kHz mono. */
class VoixTest {
	private static byte[] wav(final int cadence, final int canaux, final int bits, final int tailleAnnoncee, final short... echantillons) {
		ByteBuffer b = ByteBuffer.allocate(44 + echantillons.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + echantillons.length * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
				.putInt(16).putShort((short) 1).putShort((short) canaux).putInt(cadence).putInt(cadence * canaux * bits / 8).putShort((short) (canaux * bits / 8)).putShort((short) bits)
				.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(tailleAnnoncee);
		for (short e : echantillons) {
			b.putShort(e);
		}
		return b.array();
	}

	@Test
	void dejaEn48kHz() {
		short[] son = Voix.depuisWav(wav(48000, 1, 16, 8, (short) 1, (short) -2, (short) 3, (short) -4));
		assertEquals(4, son.length);
		assertEquals(-2, son[1]);
	}

	@Test
	void reechantillonneDepuis24kHz() {
		short[] son = Voix.depuisWav(wav(24000, 1, 16, 8, (short) 0, (short) 1000, (short) 2000, (short) 3000));
		assertEquals(8, son.length, "deux fois plus d'échantillons en 48 kHz");
		assertEquals(0, son[0]);
		assertEquals(500, son[1], "interpolation entre deux échantillons voisins");
		assertEquals(1000, son[2]);
	}

	@Test
	void stereoRameneeEnMono() {
		short[] son = Voix.depuisWav(wav(48000, 2, 16, 8, (short) 100, (short) 300, (short) -50, (short) 50));
		assertEquals(2, son.length);
		assertEquals(200, son[0], "moyenne des deux canaux");
		assertEquals(0, son[1]);
	}

	@Test
	void longueurFictiveDUnSonEnvoyeAuFilDeLEau() {
		short[] son = Voix.depuisWav(wav(48000, 1, 16, 0xFFFFFFFF, (short) 7, (short) 8, (short) 9));
		assertEquals(3, son.length, "on prend ce qui reste du fichier");
	}

	@Test
	void pcmBrutSansEnTete() {
		ByteBuffer brut = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		brut.putShort((short) 10).putShort((short) 20).putShort((short) 30).putShort((short) 40);
		assertEquals(8, Voix.depuisWav(brut.array()).length, "du PCM brut est pris pour du 24 kHz");
	}

	@Test
	void refuseAutreChoseQueDu16Bits() {
		assertThrows(IllegalStateException.class, () -> Voix.depuisWav(wav(48000, 1, 8, 4, (short) 1, (short) 2)));
	}
}
