package com.troblecodings.signals.migration;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

public class OSCommand extends CommandBase {

    @Override
    public String getName() {
        return "opensignals";
    }

    @Override
    public String getUsage(final ICommandSender sender) {
        return "/opensignals export";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void execute(final MinecraftServer server, final ICommandSender sender,
            final String[] args) throws CommandException {
        if (args.length == 1 && "export".equalsIgnoreCase(args[0])) {
            WorldExporter.start(server, sender);
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }

    @Override
    public List<String> getTabCompletions(final MinecraftServer server,
            final ICommandSender sender, final String[] args, @Nullable final BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "export");
        }
        return Collections.emptyList();
    }
}
