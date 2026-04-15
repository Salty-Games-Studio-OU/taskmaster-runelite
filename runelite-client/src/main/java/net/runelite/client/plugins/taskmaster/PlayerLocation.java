package net.runelite.client.plugins.taskmaster;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerLocation
{
    private int x;
    private int y;
    private int plane;
}
