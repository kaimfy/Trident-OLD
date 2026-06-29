package com.trident.core;

import com.trident.events.WhitelistChecker;
import com.trident.network.AutoUpdater;

import net.fabricmc.api.ModInitializer;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

public class Trident implements ModInitializer {
	public static final String MOD_ID = "trident";
	public static final String MOD_VERSION = "1.0.0";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static String themecolor = "purple";
	public static boolean access = false;
	WhitelistChecker checker = new WhitelistChecker("https://tridentclient.xyz/auth/api.php");

	@Override
	public void onInitialize() {
		access = true;// checker.isUserWhitelisted(MinecraftClient.getInstance().getSession().getUsername());
		System.out.println("access: " + access);
		if (!access) {
			return;
		}
		setThemecolor();

		// Auto-update: clean old jar, then check for new version
		AutoUpdater.cleanupOldJar();
		AutoUpdater.checkAsync();

		MinecraftClient mcclient = MinecraftClient.getInstance();
		mcclient.execute(() -> {

			setWindowIcon(mcclient);

			long handle = mcclient.getWindow().getHandle();

			GLFW.glfwSetWindowTitle(handle, "Trident 0.0.1 - " + mcclient.getSession().getUsername());
		});

	}

	private void setThemecolor() {
		SecureRandom r = new SecureRandom();
		switch (r.nextInt(2)) {
			case 0:
				themecolor = "red";
				break;
			case 1:
				themecolor = "purple";
				break;
		}
	}

	public static String getThemecolor() {
		return themecolor;
	}

	private void setWindowIcon(MinecraftClient client) {

		String imag = "assets/trident/icon64" + themecolor + ".png";

		try (MemoryStack stack = MemoryStack.stackPush()) {
			long handle = client.getWindow().getHandle();

			InputStream inputStream = Thread.currentThread()
					.getContextClassLoader().getResourceAsStream(imag);
			assert inputStream != null;
			BufferedImage image = ImageIO.read(inputStream);
			if (image == null) {
				return;
			}

			int width = image.getWidth();
			int height = image.getHeight();
			int[] pixels = new int[width * height];
			image.getRGB(0, 0, width, height, pixels, 0, width);

			ByteBuffer buffer = stack.malloc(width * height * 4);
			for (int y = height - 1; y >= 0; y--) {
				for (int x = 0; x < width; x++) {
					int pixel = pixels[y * width + x];
					buffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
					buffer.put((byte) ((pixel >> 8) & 0xFF)); // Green
					buffer.put((byte) (pixel & 0xFF)); // Blue
					buffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
				}
			}
			buffer.flip();

			GLFWImage imageBuf = GLFWImage.malloc(stack);
			imageBuf.set(width, height, buffer);
			GLFWImage.Buffer imageBufs = GLFWImage.malloc(1, stack).put(0, imageBuf);

			GLFW.glfwSetWindowIcon(handle, imageBufs);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}