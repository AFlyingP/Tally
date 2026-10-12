package tally.proof.gen;

public sealed interface Op {

  record CreateAccount(long minBalance) implements Op {}

  record Deposit(int account, long amount) implements Op {
    public Deposit {
      requireNonNegative(account);
    }
  }

  record Withdraw(int account, long amount) implements Op {
    public Withdraw {
      requireNonNegative(account);
    }
  }

  record Transfer(int source, int destination, long amount) implements Op {
    public Transfer {
      requireNonNegative(source);
      requireNonNegative(destination);
    }
  }

  record Reverse(int pick) implements Op {
    public Reverse {
      requireNonNegative(pick);
    }
  }

  record RetrySame(int pick) implements Op {
    public RetrySame {
      requireNonNegative(pick);
    }
  }

  record RetryDifferent(int pick) implements Op {
    public RetryDifferent {
      requireNonNegative(pick);
    }
  }

  record Freeze(int account) implements Op {
    public Freeze {
      requireNonNegative(account);
    }
  }

  record Close(int account) implements Op {
    public Close {
      requireNonNegative(account);
    }
  }

  private static void requireNonNegative(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("index must be non-negative");
    }
  }
}
