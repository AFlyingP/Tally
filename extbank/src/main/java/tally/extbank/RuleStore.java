package tally.extbank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RuleStore {

  public ExtbankDto.Rule insert(Connection connection, ExtbankDto.RuleRequest request, Instant now)
      throws SQLException {
    String sql =
        """
        INSERT INTO extbank.rule (creditor_account, behaviour, delay_ms, remaining, created_at)
        VALUES (?, ?, ?, ?, ?) RETURNING id
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, request.creditorAccount());
      insert.setString(2, request.behaviour().name());
      insert.setInt(3, request.delayMs());
      insert.setObject(4, request.remaining());
      insert.setObject(5, time(now));
      try (ResultSet row = insert.executeQuery()) {
        row.next();
        return new ExtbankDto.Rule(
            row.getLong("id"),
            request.creditorAccount(),
            request.behaviour(),
            request.delayMs(),
            request.remaining());
      }
    }
  }

  public List<ExtbankDto.Rule> all(Connection connection) throws SQLException {
    String sql =
        "SELECT id, creditor_account, behaviour, delay_ms, remaining FROM extbank.rule ORDER BY id";
    try (PreparedStatement select = connection.prepareStatement(sql);
        ResultSet rows = select.executeQuery()) {
      List<ExtbankDto.Rule> rules = new ArrayList<>();
      while (rows.next()) {
        rules.add(rule(rows));
      }
      return rules;
    }
  }

  public void deleteAll(Connection connection) throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM extbank.rule")) {
      delete.executeUpdate();
    }
  }

  public Optional<ExtbankDto.Rule> select(Connection connection, String creditorAccount)
      throws SQLException {
    String sql =
        "SELECT id, creditor_account, behaviour, delay_ms, remaining "
            + "FROM extbank.rule ORDER BY id FOR UPDATE";
    try (PreparedStatement select = connection.prepareStatement(sql);
        ResultSet rows = select.executeQuery()) {
      while (rows.next()) {
        ExtbankDto.Rule candidate = rule(rows);
        if ((candidate.creditorAccount() == null
                || candidate.creditorAccount().equals(creditorAccount))
            && (candidate.remaining() == null || candidate.remaining() > 0)) {
          if (candidate.remaining() != null) {
            try (PreparedStatement update =
                connection.prepareStatement(
                    "UPDATE extbank.rule SET remaining = remaining - 1 WHERE id = ?")) {
              update.setLong(1, candidate.id());
              update.executeUpdate();
            }
            candidate =
                new ExtbankDto.Rule(
                    candidate.id(),
                    candidate.creditorAccount(),
                    candidate.behaviour(),
                    candidate.delayMs(),
                    candidate.remaining() - 1);
          }
          return Optional.of(candidate);
        }
      }
      return Optional.empty();
    }
  }

  private static ExtbankDto.Rule rule(ResultSet row) throws SQLException {
    return new ExtbankDto.Rule(
        row.getLong("id"),
        row.getString("creditor_account"),
        Behaviour.valueOf(row.getString("behaviour")),
        row.getInt("delay_ms"),
        row.getObject("remaining", Integer.class));
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
