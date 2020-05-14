import gateway.Conf;
import gateway.Server;
import io.netty.channel.nio.NioEventLoopGroup;

public class Boot {

    public static void main(String[] args) {

        Conf.loadConf();

        Server server = new Server(80, new NioEventLoopGroup(), new NioEventLoopGroup(), new NioEventLoopGroup());
        server.start();
    }
}
