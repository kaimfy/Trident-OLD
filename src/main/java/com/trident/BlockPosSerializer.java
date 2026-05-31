package com.trident;

import com.google.gson.*;
import net.minecraft.util.math.BlockPos;
import java.lang.reflect.Type;

public class BlockPosSerializer implements JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
    @Override
    public JsonElement serialize(BlockPos src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", src.getX());
        obj.addProperty("y", src.getY());
        obj.addProperty("z", src.getZ());
        return obj;
    }

    @Override
    public BlockPos deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        try {
            JsonObject obj = json.getAsJsonObject();
            return new BlockPos(
                    obj.get("x").getAsInt(),
                    obj.get("y").getAsInt(),
                    obj.get("z").getAsInt()
            );
        } catch (Exception e) {
            throw new JsonParseException("Invalid BlockPos format", e);
        }
    }
}