package fear.client.events.impl;

import net.minecraft.entity.Entity;
import fear.client.events.Event;

public class EventEntitySpawn extends Event {
    private final Entity entity;

    public EventEntitySpawn(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return this.entity;
    }
}
