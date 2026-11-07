package dev.loadpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PlatformApplication {
    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("runtime-probe-tool")){
            int result=dev.loadpilot.integrations.k6.RuntimeProbeCommand.run(java.util.Arrays.copyOfRange(args,1,args.length),System.out,System.err);if(result!=0)System.exit(result);return;
        }
        SpringApplication.run(PlatformApplication.class, args);
    }
}
