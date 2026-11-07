package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.experiments.RuntimeProbeScopeApi;
import dev.loadpilot.integrations.k6.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeProbeCommandTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();@TempDir Path directory;
    int run(String[] args,ByteArrayOutputStream out,ByteArrayOutputStream error,Clock clock){return RuntimeProbeCommand.run(args,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(error,true,StandardCharsets.UTF_8),clock);}
    @Test void helpAndMalformedArgumentsNeedNoDatabaseOrCredentialsAndDoNotEchoSecrets(){
        var out=new ByteArrayOutputStream();var error=new ByteArrayOutputStream();assertThat(run(new String[]{"help"},out,error,Clock.systemUTC())).isZero();assertThat(out.toString(StandardCharsets.UTF_8)).contains("begin|prepare|capture|publish","nothing is applied or deleted");assertThat(error.size()).isZero();out.reset();
        assertThat(run(new String[]{"private-value","private-token"},out,error,Clock.systemUTC())).isEqualTo(2);assertThat(error.toString(StandardCharsets.UTF_8)).doesNotContain("private-value","private-token");assertThat(out.size()).isZero();
    }
    @Test void realCommandBeginsAndEmitsFixedManifestsFromHashBoundSnapshotWhileKeepingConnectionAndKeyPrivate() throws Exception{
        var test=new RuntimeProbeIssuerTest();test.directory=directory;
        try(var c=test.new Context()){
            var plan=(ObjectNode)json.readTree(c.original.canonicalJson());var input=json.createObjectNode();input.put("schemaVersion","experiment-input-v5").put("mode","SINGLE_RUN");input.putObject("target").put("id",c.bound.binding().targetId().toString()).put("baseUrl","https://fixture.invalid/ready");var script=input.putObject("scenario").putObject("script");script.put("approved",true).put("source",plan.path("files").path("data").path("scenario.js").asText()).put("sha256",plan.path("scriptSha256").asText());
            String canonical=input.toString();plan.put("snapshotSha256",K6PlanEnvelope.hash(canonical));String text=plan.toString();var reviewed=new RuntimeProbeScopeApi.Review(new K6PlanEnvelope.Prepared(text,K6PlanEnvelope.hash(text)),canonical,false);
            UUID id=UUID.randomUUID();var scope=new RuntimeProbeCommand.Scope(reviewed,id,List.of(test.node),test.canary,c.archives.toString(),c.reports.toString(),"operator",Map.of("operator",Base64.getEncoder().encodeToString(c.pair.getPublic().getEncoded())),"network-system","cni-agent",test.base.plusSeconds(600),null);
            Path scopeFile=directory.resolve("scope.json"),connection=directory.resolve("connection.json"),key=directory.resolve("private.pk8");Files.writeString(scopeFile,json.writeValueAsString(scope));Files.writeString(connection,json.writeValueAsString(new RuntimeProbeCommand.Material(c.server.origin(),c.server.ca,"fixture-token",1)));Files.write(key,c.pair.getPrivate().getEncoded());
            var out=new ByteArrayOutputStream();var error=new ByteArrayOutputStream();String[] begin={"begin",scopeFile.toString(),connection.toString(),key.toString()};assertThat(run(begin,out,error,c.time)).as(error.toString(StandardCharsets.UTF_8)).isZero();assertThat(json.readTree(out.toByteArray()).path("probeId").asText()).isEqualTo(id.toString());out.reset();
            int before=c.server.requests.size();String[] prepare={"prepare",scopeFile.toString(),connection.toString(),key.toString(),"node-a","ALLOWED"};assertThat(run(prepare,out,error,c.time)).as(error.toString(StandardCharsets.UTF_8)).isZero();var manifest=json.readTree(out.toByteArray());assertThat(manifest.path("kind").asText()).isEqualTo("List");assertThat(manifest.path("items")).hasSize(3);assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("fixture-token","PRIVATE KEY");assertThat(c.server.requests).hasSize(before);assertThat(c.server.requests).allMatch(r->r.startsWith("GET "));out.reset();
            var bad=(ObjectNode)json.valueToTree(scope);((ObjectNode)bad.path("review")).put("inputsCanonicalJson",canonical+" ");Files.writeString(scopeFile,bad.toString());assertThat(run(begin,out,error,c.time)).isEqualTo(2);assertThat(c.server.requests).hasSize(before);assertThat(out.size()).isZero();assertThat(error.toString(StandardCharsets.UTF_8)).doesNotContain("fixture-token",Base64.getEncoder().encodeToString(c.pair.getPrivate().getEncoded()));
        }
    }
}
