package tally.extbank;

import jakarta.validation.Valid;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControlController {

  private final DataSource dataSource;
  private final RuleStore rules;
  private final Clock clock;

  public ControlController(DataSource dataSource, RuleStore rules, Clock clock) {
    this.dataSource = dataSource;
    this.rules = rules;
    this.clock = clock;
  }

  @PostMapping("/control/rules")
  ResponseEntity<ExtbankDto.Rule> create(@Valid @RequestBody ExtbankDto.RuleRequest request)
      throws SQLException {
    ExtbankDto.Rule rule;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        rule = rules.insert(connection, request, clock.instant());
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
    return ResponseEntity.status(201).body(rule);
  }

  @GetMapping("/control/rules")
  ExtbankDto.RuleList list() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      return new ExtbankDto.RuleList(rules.all(connection));
    }
  }

  @DeleteMapping("/control/rules")
  ResponseEntity<Void> delete() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        rules.deleteAll(connection);
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
    return ResponseEntity.noContent().build();
  }
}
