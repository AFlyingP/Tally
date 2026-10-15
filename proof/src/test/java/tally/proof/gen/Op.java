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

  record PlaceHold(int account, long amount, int ttlSeconds) implements Op {
    public PlaceHold {
      requireNonNegative(account);
    }
  }

  record CaptureHold(int pick, long amount, int destination) implements Op {
    public CaptureHold {
      requireNonNegative(pick);
      requireNonNegative(destination);
    }
  }

  record ReleaseHold(int pick) implements Op {
    public ReleaseHold {
      requireNonNegative(pick);
    }
  }

  record AdvanceClock(int seconds) implements Op {}

  record CreateSchedule(int source, int destination, long amount, boolean daily, int startInSeconds)
      implements Op {
    public CreateSchedule {
      requireNonNegative(source);
      requireNonNegative(destination);
    }
  }

  record CreatePayment(int account, long amount, int behaviour) implements Op {
    public CreatePayment {
      requireNonNegative(account);
      requireNonNegative(behaviour);
    }
  }

  private static void requireNonNegative(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("index must be non-negative");
    }
  }
}
