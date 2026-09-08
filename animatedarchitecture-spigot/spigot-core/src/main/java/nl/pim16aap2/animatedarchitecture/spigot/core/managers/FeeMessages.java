package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import java.util.Objects;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.republicraft.rcui.api.MessageBundle;
import net.republicraft.rcui.api.RCUI;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** RC-owned creation payment presentation; upstream native text adapters remain separate. */
final class FeeMessages {
    private final MessageBundle bundle;
    FeeMessages(JavaPlugin plugin) {
        this(RCUI.messages(plugin).register(plugin,"rcdoors-fees","fees-messages.yml"));
    }
    FeeMessages(MessageBundle bundle) { this.bundle = Objects.requireNonNull(bundle); }
    void send(CommandSender sender,String key,Object... arguments) {
        if(arguments.length % 2 != 0) throw new IllegalArgumentException("Message arguments require pairs");
        TagResolver[] values = new TagResolver[arguments.length/2];
        for(int i=0;i<arguments.length;i+=2)
            values[i/2] = Placeholder.unparsed(String.valueOf(arguments[i]),String.valueOf(arguments[i+1]));
        bundle.send((net.kyori.adventure.audience.Audience)sender,key,values);
    }
}
